using Microsoft.AspNetCore.Identity.Data;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using System.Text.RegularExpressions;
using webapi.Data;
using webapi.Models;


namespace webapi.Controllers
{
    [ApiController]
    [Route("api/[controller]")]
    public class InspectionController : ControllerBase
    {
        private readonly AppDbContext _context;
        private readonly IWebHostEnvironment _env;

        public InspectionController(AppDbContext context, IWebHostEnvironment env)
        {
            _context = context;
            _env = env;
        }

        // 旧接口：提交简单点检（兼容）
        [HttpPost("submit")]
        public async Task<IActionResult> SubmitInspection([FromBody] InspectionRequest request)
        {
            if (request == null || string.IsNullOrEmpty(request.EmployeeId))
                return BadRequest("工号不能为空");

            // 验证工号是否为点检资格人员
            var isQualified = await _context.QualifiedInspectors
                .AnyAsync(o => o.EmployeeId == request.EmployeeId);
            if (!isQualified)
                return BadRequest(new { success = false, message = "该工号无点检资格" });

            // 计算 pending_photo 状态：检查是否有异常+requirePhoto 的项
            var requirePhotoItems = await _context.InspectionTemplates
                .Where(t => t.DeviceModel == request.DeviceName && t.RequirePhoto)
                .Select(t => t.ItemName)
                .ToListAsync();

            var hasAbnormalMissingPhoto = request.CheckItems.Any(c =>
                requirePhotoItems.Contains(c.ItemName) && c.Result == "异常");

            var record = new InspectionRecord
            {
                EmployeeId = request.EmployeeId,
                DeviceName = request.DeviceName,
                DeviceModel = request.DeviceName, // 暂时兼容
                InspectionTime = DateTime.Now,
                ResultsJson = System.Text.Json.JsonSerializer.Serialize(request.CheckItems),
                Status = hasAbnormalMissingPhoto ? InspectionStatus.PendingPhoto : InspectionStatus.Submitted
            };

            _context.InspectionRecords.Add(record);
            await _context.SaveChangesAsync();

            return Ok(new { success = true, message = "点检数据已保存" });
        }

        // 1. 根据设备型号获取点检模板
        [HttpGet("templates/{deviceModel}")]
        public async Task<IActionResult> GetTemplates(string deviceModel, [FromQuery] string? frequency = null)
        {
            var query = _context.InspectionTemplates
                .Where(t => t.DeviceModel == deviceModel);

            if (!string.IsNullOrEmpty(frequency))
                query = query.Where(t => t.Frequency == frequency);

            var templates = await query
                .OrderBy(t => t.SortOrder)
                .ToListAsync();

            return Ok(templates);
        }

        // 2. 提交完整点检记录（包含多个点检结果）
        [HttpPost("submit-full")]
        public async Task<IActionResult> SubmitFullInspection([FromBody] FullInspectionRequest request)
        {
            if (request == null || string.IsNullOrEmpty(request.EmployeeId))
                return BadRequest("工号不能为空");

            // 验证工号是否为点检资格人员
            var isQualified = await _context.QualifiedInspectors
                .AnyAsync(o => o.EmployeeId == request.EmployeeId);
            if (!isQualified)
                return BadRequest(new { success = false, message = "该工号无点检资格" });

            var now = DateTime.Now;
            var frequency = string.IsNullOrEmpty(request.Frequency) ? "日" : request.Frequency;
            var periodKey = GeneratePeriodKey(frequency, now);

            // ===== 照片迁移：删除旧记录前，先捕获旧照片信息 =====
            List<(string PhotoPath, string ThumbnailPath, string ItemName, int PhotoOrder, string UploadedBy)> oldPhotos = new();
            int? oldRecordId = null;

            var existingRecord = await _context.InspectionRecords
                .AsTracking()
                .FirstOrDefaultAsync(r => r.DeviceModel == request.DeviceModel
                    && r.Frequency == frequency
                    && r.PeriodKey == periodKey);
            if (existingRecord != null)
            {
                oldRecordId = existingRecord.Id;
                // 在级联删除之前，捕获所有照片记录到内存
                var oldPhotoRows = await _context.InspectionPhotos
                    .Where(p => p.RecordId == existingRecord.Id)
                    .Select(p => new {
                        p.PhotoPath, p.ThumbnailPath, p.ItemName,
                        p.PhotoOrder, p.UploadedBy
                    })
                    .ToListAsync();

                oldPhotos = oldPhotoRows
                    .Select(p => (p.PhotoPath, p.ThumbnailPath ?? "",
                        p.ItemName, p.PhotoOrder, p.UploadedBy))
                    .ToList();

                _context.InspectionRecords.Remove(existingRecord);
                await _context.SaveChangesAsync(); // 触发级联删除 inspection_photos 行
            }

            // 创建新记录
            // 计算 pending_photo 状态
            var requirePhotoItems = await _context.InspectionTemplates
                .Where(t => t.DeviceModel == request.DeviceModel && t.RequirePhoto)
                .Select(t => t.ItemName)
                .ToListAsync();

            var hasAbnormalMissingPhoto = request.Results.Any(r =>
                requirePhotoItems.Contains(r.ItemName) && !r.IsNormal);

            var record = new InspectionRecord
            {
                EmployeeId = request.EmployeeId,
                DeviceModel = request.DeviceModel,
                DeviceName = request.DeviceModel,
                InspectionTime = now,
                Status = hasAbnormalMissingPhoto ? InspectionStatus.PendingPhoto : InspectionStatus.Submitted,
                Frequency = frequency,
                PeriodKey = periodKey,
                ResultsJson = ""
            };

            _context.InspectionRecords.Add(record);
            await _context.SaveChangesAsync();

            // 创建结果明细
            foreach (var item in request.Results)
            {
                var result = new InspectionResult
                {
                    RecordId = record.Id,
                    ItemName = item.ItemName,
                    ResultValue = item.ResultValue,
                    IsNormal = item.IsNormal,
                    Remark = item.Remark ?? ""
                };
                _context.InspectionResults.Add(result);
            }

            await _context.SaveChangesAsync();

            // ===== 照片迁移：将旧照片恢复到新 recordId =====
            if (oldRecordId.HasValue && oldPhotos.Count > 0)
            {
                var webRootPath = _env.WebRootPath;
                var oldDir = Path.Combine(webRootPath, "photos",
                    oldRecordId.Value.ToString());
                var nowDate = record.InspectionTime;
                var newDir = Path.Combine(webRootPath, "photos",
                    nowDate.Year.ToString(), nowDate.Month.ToString("D2"),
                    record.Id.ToString());

                // 插入新的 InspectionPhoto 行
                foreach (var op in oldPhotos)
                {
                    // 将旧路径中的 recordId 替换为新的
                    var newPhotoPath = op.PhotoPath.Replace(
                        $"/{oldRecordId.Value}/", $"/{record.Id}/");
                    var newThumbPath = op.ThumbnailPath?.Replace(
                        $"/{oldRecordId.Value}/", $"/{record.Id}/");

                    _context.InspectionPhotos.Add(new InspectionPhoto
                    {
                        RecordId = record.Id,
                        ItemName = op.ItemName,
                        PhotoPath = newPhotoPath ?? op.PhotoPath,
                        ThumbnailPath = newThumbPath,
                        PhotoOrder = op.PhotoOrder,
                        UploadedBy = op.UploadedBy,
                        CreatedAt = DateTime.Now
                    });
                }

                // 迁移文件目录（如果旧目录还存在）
                if (Directory.Exists(oldDir))
                {
                    if (!Directory.Exists(newDir))
                        Directory.CreateDirectory(Path.GetDirectoryName(newDir)!);

                    // 移动所有旧文件到新目录
                    foreach (var file in Directory.GetFiles(oldDir))
                    {
                        var fileName = Path.GetFileName(file);
                        var destFile = Path.Combine(newDir, fileName);
                        if (!System.IO.File.Exists(destFile))
                            System.IO.File.Move(file, destFile);
                    }
                    // 删除旧目录（如果已空）
                    if (!Directory.EnumerateFileSystemEntries(oldDir).Any())
                        Directory.Delete(oldDir);
                }

                await _context.SaveChangesAsync();

                // 如果旧照片已覆盖所有异常项，更新状态
                if (record.Status == InspectionStatus.PendingPhoto)
                {
                    var photoItems = oldPhotos.Select(p => p.ItemName).Distinct().ToHashSet();
                    var abnormalItems = request.Results
                        .Where(r => !r.IsNormal && requirePhotoItems.Contains(r.ItemName))
                        .Select(r => r.ItemName);
                    var allCovered = abnormalItems.All(item => photoItems.Contains(item));
                    if (allCovered)
                    {
                        record.Status = InspectionStatus.Submitted;
                        await _context.SaveChangesAsync();
                    }
                }
            }

            return Ok(new { success = true, recordId = record.Id, message = "点检数据已保存" });
        }

        // 获取所有设备型号
        [HttpGet("devices")]
        public async Task<IActionResult> GetDevices()
        {
            var devices = await _context.InspectionTemplates
                .Select(t => new {
                    deviceModel = t.DeviceModel,
                    deviceName = t.DeviceModel  // 暂时用型号作为名称
                })
                .Distinct()
                .ToListAsync();

            return Ok(devices);
        }

        [HttpGet("records/monthly")]
        public async Task<IActionResult> GetMonthlyRecords(
            string deviceModel, int year, int month,
            int page = 1, int pageSize = 100)
        {
            try
            {
                var startDate = new DateTime(year, month, 1);
                var endDate = startDate.AddMonths(1).AddTicks(-1);

                var query =
                    from r in _context.InspectionRecords
                    join res in _context.InspectionResults on r.Id equals res.RecordId
                    where r.DeviceModel == deviceModel
                          && r.InspectionTime >= startDate
                          && r.InspectionTime <= endDate
                    select new MonthlyRecordDto
                    {
                        RecordId = r.Id,
                        ItemName = res.ItemName,
                        InspectionDay = r.InspectionTime.Day,
                        ResultValue = res.ResultValue,
                        Remark = res.Remark,
                        IsNormal = res.IsNormal,
                        Status = r.Status
                    };

                var totalCount = await query.CountAsync();

                var records = await query
                    .OrderByDescending(r => r.InspectionDay)
                    .ThenByDescending(r => r.ItemName)
                    .Skip((page - 1) * pageSize)
                    .Take(pageSize)
                    .ToListAsync();

                // 转换结果值：正常 → ○，异常 → ×
                foreach (var record in records)
                {
                    if (record.ResultValue == "正常")
                        record.ResultValue = "○";
                    else if (record.ResultValue == "异常")
                        record.ResultValue = "×";
                }

                return Ok(new PagedResponse<MonthlyRecordDto>
                {
                    Items = records,
                    Page = page,
                    PageSize = pageSize,
                    TotalCount = totalCount
                });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { error = ex.Message });
            }
        }

        // 4. 保存点检记录（事务 + 批量 upsert 优化版）
        [HttpPost("records/save")]
        public async Task<IActionResult> SaveDailyRecord([FromBody] SaveDailyRecordRequest request)
        {
            try
            {
                var inspectionDate = request.InspectionMonth;
                var employeeId = string.IsNullOrEmpty(request.EmployeeId) ? "web" : request.EmployeeId;
                var frequency = string.IsNullOrEmpty(request.Frequency) ? "日" : request.Frequency;

                // 收集所有周期键
                var periodKeys = request.Results
                    .Select(item => GeneratePeriodKey(frequency,
                        new DateTime(inspectionDate.Year, inspectionDate.Month, item.Day)))
                    .Distinct()
                    .ToList();

                using var transaction = await _context.Database.BeginTransactionAsync();
                try
                {
                    // ===== 批量查询：查出所有已有的 records =====
                    var existingRecords = await _context.InspectionRecords
                        .AsTracking()
                        .Where(r => r.DeviceModel == request.DeviceModel
                                    && r.Frequency == frequency
                                    && periodKeys.Contains(r.PeriodKey))
                        .ToListAsync();

                    var existingRecordMap = existingRecords
                        .ToDictionary(r => r.PeriodKey);

                    // 补齐缺失的 records
                    foreach (var periodKey in periodKeys)
                    {
                        if (!existingRecordMap.ContainsKey(periodKey))
                        {
                            var dayItem = request.Results.FirstOrDefault(r =>
                                GeneratePeriodKey(frequency,
                                    new DateTime(inspectionDate.Year, inspectionDate.Month, r.Day)) == periodKey);
                            var day = dayItem?.Day ?? 1;

                            var newRecord = new InspectionRecord
                            {
                                EmployeeId = employeeId,
                                DeviceModel = request.DeviceModel,
                                DeviceName = request.DeviceModel,
                                InspectionTime = new DateTime(inspectionDate.Year, inspectionDate.Month, day),
                                Status = InspectionStatus.Submitted,
                                ResultsJson = "",
                                Frequency = frequency,
                                PeriodKey = periodKey
                            };
                            _context.InspectionRecords.Add(newRecord);
                            await _context.SaveChangesAsync();
                            existingRecordMap[periodKey] = newRecord;
                        }
                    }

                    // ===== 批量 upsert results =====
                    var upsertSql = @"
                INSERT INTO inspection_results (record_id, item_name, result_value, is_normal, remark)
                VALUES ({0}, {1}, {2}, {3}, {4})
                ON DUPLICATE KEY UPDATE
                    result_value = VALUES(result_value),
                    is_normal = VALUES(is_normal),
                    remark = VALUES(remark)";

                    foreach (var item in request.Results)
                    {
                        // 转换前端传来的值：○ → 正常，× → 异常
                        string saveValue = item.ResultValue;
                        if (saveValue == "○")
                            saveValue = "正常";
                        else if (saveValue == "×")
                            saveValue = "异常";

                        var periodKey = GeneratePeriodKey(frequency,
                            new DateTime(inspectionDate.Year, inspectionDate.Month, item.Day));
                        if (!existingRecordMap.TryGetValue(periodKey, out var record))
                            continue;

                        await _context.Database.ExecuteSqlRawAsync(upsertSql,
                            record.Id, item.ItemName, saveValue, item.IsNormal, item.Remark);
                    }

                    // ===== 计算每个 record 的 pending_photo 状态 =====
                    var requirePhotoItems = await _context.InspectionTemplates
                        .Where(t => t.DeviceModel == request.DeviceModel && t.RequirePhoto)
                        .Select(t => t.ItemName)
                        .ToListAsync();

                    var recordIds = new List<int>();
                    var pendingPhotoItems = new List<object>();

                    foreach (var kvp in existingRecordMap)
                    {
                        var rec = kvp.Value;
                        var recPeriodKey = kvp.Key;

                        // 收集该 record 的所有异常+requirePhoto 项（按 periodKey 匹配）
                        var abnormalPhotoItems = request.Results
                            .Where(r => GeneratePeriodKey(frequency,
                                    new DateTime(inspectionDate.Year, inspectionDate.Month, r.Day)) == recPeriodKey
                                && requirePhotoItems.Contains(r.ItemName)
                                && !r.IsNormal)
                            .Select(r => r.ItemName)
                            .ToList();

                        if (abnormalPhotoItems.Count > 0)
                        {
                            // 重检场景：删除旧照片（DB + 文件），强制重新上传
                            var oldPhotos = await _context.InspectionPhotos
                                .Where(p => p.RecordId == rec.Id
                                    && abnormalPhotoItems.Contains(p.ItemName))
                                .ToListAsync();

                            foreach (var op in oldPhotos)
                            {
                                var photoFull = Path.Combine(_env.WebRootPath,
                                    op.PhotoPath.TrimStart('/'));
                                var thumbFull = op.ThumbnailPath != null
                                    ? Path.Combine(_env.WebRootPath, op.ThumbnailPath.TrimStart('/'))
                                    : null;
                                if (System.IO.File.Exists(photoFull))
                                    System.IO.File.Delete(photoFull);
                                if (thumbFull != null && System.IO.File.Exists(thumbFull))
                                    System.IO.File.Delete(thumbFull);
                            }
                            _context.InspectionPhotos.RemoveRange(oldPhotos);
                            await _context.SaveChangesAsync();

                            // 旧照片已删除 → 全部标记为待上传
                            rec.Status = InspectionStatus.PendingPhoto;
                            pendingPhotoItems.Add(new
                            {
                                recordId = rec.Id,
                                periodKey = recPeriodKey,
                                missingItems = abnormalPhotoItems
                            });
                        }
                        else
                        {
                            rec.Status = InspectionStatus.Submitted;
                        }

                        recordIds.Add(rec.Id);
                    }

                    await _context.SaveChangesAsync();
                    await transaction.CommitAsync();
                    return Ok(new
                    {
                        success = true,
                        message = "保存成功",
                        recordIds = recordIds,
                        pendingPhotoItems = pendingPhotoItems
                    });
                }
                catch
                {
                    await transaction.RollbackAsync();
                    throw;
                }
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { success = false, message = ex.Message });
            }
        }
        [HttpGet("signatures/get")]
        public async Task<IActionResult> GetSignatures(string deviceModel, int year, int month)
        {
            try
            {
                var signature = await _context.InspectionSignatures
                    .FirstOrDefaultAsync(s => s.DeviceModel == deviceModel
                        && s.Year == year
                        && s.Month == month);

                if (signature == null)
                {
                    return Ok(new
                    {
                        approver = "",
                        confirmer = "",
                        operatorName = ""  // 修改这里
                    });
                }

                return Ok(new
                {
                    approver = signature.Approver ?? "",
                    confirmer = signature.Confirmer ?? "",
                    operatorName = signature.OperatorName ?? ""
                });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { error = ex.Message });
            }
        }


        [HttpPost("signatures/save")]
        public async Task<IActionResult> SaveSignatures([FromBody] SaveSignaturesRequest request)
        {
            try
            {
                var existing = await _context.InspectionSignatures
                    .AsTracking()
                    .FirstOrDefaultAsync(s => s.DeviceModel == request.DeviceModel
                        && s.Year == request.Year
                        && s.Month == request.Month);

                if (existing != null)
                {
                    existing.Approver = request.Approver ?? "";
                    existing.Confirmer = request.Confirmer ?? "";
                    existing.OperatorName = request.OperatorName ?? "";  // 修改这里
                    existing.UpdatedAt = DateTime.Now;
                }
                else
                {
                    var newSignature = new InspectionSignature
                    {
                        DeviceModel = request.DeviceModel,
                        Year = request.Year,
                        Month = request.Month,
                        Approver = request.Approver ?? "",
                        Confirmer = request.Confirmer ?? "",
                        OperatorName = request.OperatorName ?? "",  // 修改这里
                        CreatedAt = DateTime.Now,
                        UpdatedAt = DateTime.Now
                    };
                    _context.InspectionSignatures.Add(newSignature);
                }

                await _context.SaveChangesAsync();
                return Ok(new { success = true, message = "签名保存成功" });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { success = false, message = ex.Message });
            }
        }

        // 7. 获取用户列表（用于下拉菜单），支持分页
        [HttpGet("users/list")]
        public async Task<IActionResult> GetUserList(int page = 1, int pageSize = 100)
        {
            var query = _context.InspectionUsers
                .Select(u => new { u.Username, u.FullName, u.Role });

            var totalCount = await query.CountAsync();

            var users = await query
                .OrderBy(u => u.Username)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync();

            return Ok(new PagedResponse<object>
            {
                Items = users.Cast<object>().ToList(),
                Page = page,
                PageSize = pageSize,
                TotalCount = totalCount
            });
        }

        // 8. 用户登录验证
        [HttpPost("users/login")]
        public async Task<IActionResult> Login([FromBody] LoginRequest request)
        {
            var user = await _context.InspectionUsers
                .FirstOrDefaultAsync(u => u.Username == request.Username && u.Password == request.Password);

            if (user == null)
            {
                return Unauthorized(new { success = false, message = "用户名或密码错误" });
            }

            return Ok(new
            {
                success = true,
                username = user.Username,
                fullName = user.FullName,
                role = user.Role
            });
        }

        // 9. 自动签名（登录后自动签署对应角色）
        [HttpPost("signatures/auto-sign")]
        public async Task<IActionResult> AutoSign([FromBody] AutoSignRequest request)
        {
            try
            {
                var existing = await _context.InspectionSignatures
                    .AsTracking()
                    .FirstOrDefaultAsync(s => s.DeviceModel == request.DeviceModel
                        && s.Year == request.Year
                        && s.Month == request.Month);

                if (existing != null)
                {
                    // 根据角色更新对应字段
                    if (request.Role == "operator")
                        existing.OperatorName = request.FullName;
                    else if (request.Role == "confirmer")
                        existing.Confirmer = request.FullName;
                    else if (request.Role == "approver")
                        existing.Approver = request.FullName;

                    existing.UpdatedAt = DateTime.Now;
                }
                else
                {
                    var newSignature = new InspectionSignature
                    {
                        DeviceModel = request.DeviceModel,
                        Year = request.Year,
                        Month = request.Month,
                        Approver = request.Role == "approver" ? request.FullName : "",
                        Confirmer = request.Role == "confirmer" ? request.FullName : "",
                        OperatorName = request.Role == "operator" ? request.FullName : "",
                        CreatedAt = DateTime.Now,
                        UpdatedAt = DateTime.Now
                    };
                    _context.InspectionSignatures.Add(newSignature);
                }

                await _context.SaveChangesAsync();
                return Ok(new { success = true, message = "签名成功" });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { success = false, message = ex.Message });
            }
        }

        // 14. 获取点检资格人员列表，支持分页
        [HttpGet("operators/list")]
        public async Task<IActionResult> GetOperatorList(int page = 1, int pageSize = 100)
        {
            var query = _context.QualifiedInspectors
                .Select(o => new { o.EmployeeId, o.LastName });

            var totalCount = await query.CountAsync();

            var operators = await query
                .OrderBy(o => o.EmployeeId)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
                .ToListAsync();

            return Ok(new PagedResponse<object>
            {
                Items = operators.Cast<object>().ToList(),
                Page = page,
                PageSize = pageSize,
                TotalCount = totalCount
            });
        }

        // 15. 新增点检资格人员（支持单个/批量）
        [HttpPost("operators/add")]
        public async Task<IActionResult> AddOperators([FromBody] List<AddOperatorRequest> requests)
        {
            if (requests == null || requests.Count == 0)
                return BadRequest(new { success = false, message = "请求数据不能为空" });

            var existingIds = await _context.QualifiedInspectors
                .Select(o => o.EmployeeId)
                .ToListAsync();

            var existingSet = new HashSet<string>(existingIds);
            var added = 0;

            foreach (var req in requests)
            {
                if (string.IsNullOrEmpty(req.EmployeeId) || string.IsNullOrEmpty(req.LastName))
                    continue;

                if (existingSet.Contains(req.EmployeeId))
                    return BadRequest(new { success = false, message = $"工号 {req.EmployeeId} 已存在" });

                _context.QualifiedInspectors.Add(new QualifiedInspector
                {
                    EmployeeId = req.EmployeeId,
                    LastName = req.LastName
                });
                existingSet.Add(req.EmployeeId);
                added++;
            }

            await _context.SaveChangesAsync();
            return Ok(new { success = true, message = $"已添加 {added} 名点检资格人员" });
        }

        // 16. 移除点检资格人员
        [HttpDelete("operators/{employeeId}")]
        public async Task<IActionResult> DeleteOperator(string employeeId)
        {
            var operator_ = await _context.QualifiedInspectors
                .AsTracking()
                .FirstOrDefaultAsync(o => o.EmployeeId == employeeId);

            if (operator_ == null)
                return NotFound(new { success = false, message = $"工号 {employeeId} 不存在" });

            _context.QualifiedInspectors.Remove(operator_);
            await _context.SaveChangesAsync();

            return Ok(new { success = true, message = $"已移除点检资格人员 {employeeId}" });
        }

        // 17. 验证工号是否为点检资格人员
        [HttpGet("operators/validate/{employeeId}")]
        public async Task<IActionResult> ValidateOperator(string employeeId)
        {
            var inspector = await _context.QualifiedInspectors
                .FirstOrDefaultAsync(o => o.EmployeeId == employeeId);

            if (inspector == null)
                return Ok(new { valid = false });

            return Ok(new
            {
                valid = true,
                employeeId = inspector.EmployeeId,
                lastName = inspector.LastName
            });
        }

        // 18. 获取指定设备+年月的每日点检者姓（窗口函数优化版）
        [HttpGet("operators/daily")]
        public async Task<IActionResult> GetDailyOperators(string deviceModel, int year, int month)
        {
            var startDate = new DateTime(year, month, 1);
            var endDate = startDate.AddMonths(1).AddTicks(-1);

            // 用 ROW_NUMBER() 窗口函数在数据库端按日分组取最新记录
            // 注意：列别名必须与 DailyOperatorRaw 属性名大小写一致，
            // SqlQueryRaw 不做 snake_case→PascalCase 转换，只做大小写不敏感精确匹配
            var sql = @"
                SELECT day AS Day, employee_id AS EmployeeId FROM (
                    SELECT
                        DAY(inspection_time) as day,
                        employee_id,
                        ROW_NUMBER() OVER (PARTITION BY DAY(inspection_time) ORDER BY inspection_time DESC) as rn
                    FROM inspection_records
                    WHERE device_model = {0}
                        AND inspection_time >= {1}
                        AND inspection_time <= {2}
                ) t WHERE rn = 1";

            var dailyRecords = await _context.Database
                .SqlQueryRaw<DailyOperatorRaw>(sql, deviceModel, startDate, endDate)
                .ToListAsync();

            // JOIN qualified_inspectors 获取姓
            var employeeIds = dailyRecords.Select(r => r.EmployeeId).Distinct().ToList();
            Dictionary<string, string> inspectors;
            if (employeeIds.Count > 0)
            {
                inspectors = await _context.QualifiedInspectors
                    .Where(o => employeeIds.Contains(o.EmployeeId))
                    .ToDictionaryAsync(o => o.EmployeeId, o => o.LastName);
            }
            else
            {
                inspectors = new Dictionary<string, string>();
            }

            var result = new Dictionary<string, string>();
            foreach (var dr in dailyRecords)
            {
                result[dr.Day.ToString()] = inspectors.TryGetValue(dr.EmployeeId, out var name) ? name : "";
            }

            return Ok(new
            {
                deviceModel,
                year,
                month,
                inspectors = result
            });
        }

        // 10. 签名统计：设备总数 vs 当前用户已签数量
        [HttpGet("signatures/sign-summary")]
        public async Task<IActionResult> GetSignSummary(int year, int month, string role, string fullName)
        {
            try
            {
                // 所有设备型号（从模板表去重）
                var totalDevices = await _context.InspectionTemplates
                    .Select(t => t.DeviceModel)
                    .Distinct()
                    .CountAsync();

                // 当前用户已签设备数
                var signedCount = 0;
                if (!string.IsNullOrEmpty(role) && !string.IsNullOrEmpty(fullName))
                {
                    var query = _context.InspectionSignatures
                        .Where(s => s.Year == year && s.Month == month);

                    signedCount = role switch
                    {
                        "operator" => await query.CountAsync(s => s.OperatorName == fullName),
                        "confirmer" => await query.CountAsync(s => s.Confirmer == fullName),
                        "approver" => await query.CountAsync(s => s.Approver == fullName),
                        _ => 0
                    };
                }

                var unsignedCount = totalDevices - signedCount;

                return Ok(new
                {
                    totalDevices,
                    signedCount,
                    unsignedCount
                });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { error = ex.Message });
            }
        }

        // 11. 批量检查签名完整性（一键导出前验证）
        [HttpGet("signatures/check-all")]
        public async Task<IActionResult> CheckAllSignatures(int year, int month)
        {
            try
            {
                var allDevices = await _context.InspectionTemplates
                    .Select(t => t.DeviceModel)
                    .Distinct()
                    .ToListAsync();

                var signatures = await _context.InspectionSignatures
                    .Where(s => s.Year == year && s.Month == month)
                    .ToListAsync();

                var sigMap = signatures.ToDictionary(s => s.DeviceModel);

                var devices = allDevices.Select(d =>
                {
                    var sig = sigMap.GetValueOrDefault(d);
                    return new
                    {
                        deviceModel = d,
                        approver = sig?.Approver ?? "",
                        confirmer = sig?.Confirmer ?? "",
                        operatorName = sig?.OperatorName ?? "",
                        isComplete = !string.IsNullOrEmpty(sig?.Approver)
                                  && !string.IsNullOrEmpty(sig?.Confirmer)
                                  && !string.IsNullOrEmpty(sig?.OperatorName)
                    };
                }).ToList();

                return Ok(new
                {
                    year,
                    month,
                    totalDevices = allDevices.Count,
                    completeCount = devices.Count(d => d.isComplete),
                    incompleteCount = devices.Count(d => !d.isComplete),
                    devices
                });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { error = ex.Message });
            }
        }

        // 12. 获取设备各频率可用状态
        [HttpGet("frequencies-available")]
        public async Task<IActionResult> GetFrequenciesAvailable(string deviceModel)
        {
            var now = DateTime.Now;

            // 检查该设备是否有各频率的模板
            var hasDaily   = await _context.InspectionTemplates.AnyAsync(t => t.DeviceModel == deviceModel && t.Frequency == "日");
            var hasWeekly  = await _context.InspectionTemplates.AnyAsync(t => t.DeviceModel == deviceModel && t.Frequency == "周");
            var hasMonthly = await _context.InspectionTemplates.AnyAsync(t => t.DeviceModel == deviceModel && t.Frequency == "月");

            // 生成各频率当前周期键
            var dailyPeriodKey   = GeneratePeriodKey("日", now);
            var weeklyPeriodKey  = GeneratePeriodKey("周", now);
            var monthlyPeriodKey = GeneratePeriodKey("月", now);

            // 判断各频率是否"已完成" = 存在记录 AND 记录中所有项都正常（无异常）
            // 如果有异常项 → 保持可用，需重新点检
            var dailyDone   = hasDaily   && await IsPeriodAllNormal(deviceModel, "日", dailyPeriodKey);
            var weeklyDone  = hasWeekly  && await IsPeriodAllNormal(deviceModel, "周", weeklyPeriodKey);
            var monthlyDone = hasMonthly && await IsPeriodAllNormal(deviceModel, "月", monthlyPeriodKey);

            return Ok(new
            {
                daily   = new { available = hasDaily   && !dailyDone,   periodKey = dailyPeriodKey },
                weekly  = new { available = hasWeekly  && !weeklyDone,  periodKey = weeklyPeriodKey },
                monthly = new { available = hasMonthly && !monthlyDone, periodKey = monthlyPeriodKey }
            });
        }

        /// <summary>
        /// 检查指定设备+频率+周期是否全部正常（无异常项）
        /// 存在至少一条记录且该记录所有 InspectionResult.IsNormal 均为 true 才返回 true
        /// </summary>
        private async Task<bool> IsPeriodAllNormal(string deviceModel, string frequency, string periodKey)
        {
            return await _context.InspectionRecords
                .Where(r => r.DeviceModel == deviceModel
                    && r.Frequency == frequency
                    && r.PeriodKey == periodKey)
                .AnyAsync(r =>
                    !_context.InspectionResults.Any(res =>
                        res.RecordId == r.Id && !res.IsNormal
                    )
                );
        }

        // 13. 按频率统计摘要（日/周/月各自独立统计）
        [HttpGet("frequency-summary")]
        public async Task<IActionResult> GetFrequencySummary(int year, int month)
        {
            var startDate = new DateTime(year, month, 1);
            var endDate = startDate.AddMonths(1).AddTicks(-1);

            var daily   = await BuildFrequencyStats("日", year, month, startDate, endDate);
            var weekly  = await BuildFrequencyStats("周", year, month, startDate, endDate);
            var monthly = await BuildFrequencyStats("月", year, month, startDate, endDate);

            return Ok(new { daily, weekly, monthly });
        }

        // 13b. 获取未点检的必须点检设备 location 列表（供手机端使用）
        /// <summary>
        /// 按日/周/月频率，返回当前周期内未点检的"必须点检"设备的 device_location，
        /// 合并为一个列表，每条标注频率。手机端在工号验证通过后及点检提交返回后调用。
        /// </summary>
        [HttpGet("uninspected-mandatory-locations")]
        public async Task<IActionResult> GetUninspectedMandatoryLocations()
        {
            var now = DateTime.Now;
            var today = now.Date;

            // 日检周期：当天
            var dayStart = today;
            var dayEnd = today.AddDays(1).AddTicks(-1);

            // 周检周期：本周一 ~ 本周日
            int dayOfWeek = (int)now.DayOfWeek;
            int diff = dayOfWeek == 0 ? 6 : dayOfWeek - 1;
            var monday = today.AddDays(-diff);
            var sunday = monday.AddDays(7).AddTicks(-1);

            // 月检周期：当月 1 日 ~ 月末
            var monthStart = new DateTime(now.Year, now.Month, 1);
            var monthEnd = monthStart.AddMonths(1).AddTicks(-1);

            var uninspectedList = new List<object>();
            var abnormalList = new List<object>();

            async Task CollectData(string frequency, string label, DateTime periodStart, DateTime periodEnd)
            {
                // ===== 未点检：必须点检设备中无任何记录的 =====
                var mandatoryModels = await _context.InspectionTemplates
                    .Where(t => t.Frequency == frequency && t.IsMandatory)
                    .Select(t => t.DeviceModel)
                    .Distinct()
                    .ToListAsync();

                if (mandatoryModels.Count > 0)
                {
                    var inspectedModels = await _context.InspectionRecords
                        .Where(r => mandatoryModels.Contains(r.DeviceModel)
                                    && r.Frequency == frequency
                                    && r.InspectionTime >= periodStart
                                    && r.InspectionTime <= periodEnd)
                        .Select(r => r.DeviceModel)
                        .Distinct()
                        .ToListAsync();

                    var inspectedSet = new HashSet<string>(inspectedModels);
                    var uninspectedModels = mandatoryModels
                        .Where(m => !inspectedSet.Contains(m))
                        .ToList();

                    if (uninspectedModels.Count > 0)
                    {
                        var locations = await _context.Devices
                            .Where(d => uninspectedModels.Contains(d.DeviceModel)
                                        && d.DeviceLocation != null
                                        && d.DeviceLocation != "")
                            .Select(d => d.DeviceLocation!)
                            .Distinct()
                            .ToListAsync();

                        foreach (var loc in locations)
                            uninspectedList.Add(new { frequency = label, location = loc });
                    }
                }

                // ===== 异常点检：全部设备（必须+选择）中有异常结果的 =====
                var allDeviceModels = await _context.InspectionTemplates
                    .Where(t => t.Frequency == frequency)
                    .Select(t => t.DeviceModel)
                    .Distinct()
                    .ToListAsync();

                if (allDeviceModels.Count > 0)
                {
                    var records = await _context.InspectionRecords
                        .Where(r => allDeviceModels.Contains(r.DeviceModel)
                                    && r.Frequency == frequency
                                    && r.InspectionTime >= periodStart
                                    && r.InspectionTime <= periodEnd)
                        .ToListAsync();

                    if (records.Count > 0)
                    {
                        var recordIds = records.Select(r => r.Id).ToList();
                        var abnormalRecordIds = new HashSet<int>();
                        const int batchSize = 500;
                        for (int i = 0; i < recordIds.Count; i += batchSize)
                        {
                            var batch = recordIds.Skip(i).Take(batchSize).ToList();
                            var abnormalIds = await _context.InspectionResults
                                .Where(res => batch.Contains(res.RecordId) && !res.IsNormal)
                                .Select(res => res.RecordId)
                                .Distinct()
                                .ToListAsync();
                            foreach (var id in abnormalIds)
                                abnormalRecordIds.Add(id);
                        }

                        if (abnormalRecordIds.Count > 0)
                        {
                            var abnormalModels = records
                                .Where(r => abnormalRecordIds.Contains(r.Id))
                                .Select(r => r.DeviceModel)
                                .Distinct()
                                .ToList();

                            var locations = await _context.Devices
                                .Where(d => abnormalModels.Contains(d.DeviceModel)
                                            && d.DeviceLocation != null
                                            && d.DeviceLocation != "")
                                .Select(d => d.DeviceLocation!)
                                .Distinct()
                                .ToListAsync();

                            foreach (var loc in locations)
                                abnormalList.Add(new { frequency = label, location = loc });
                        }
                    }
                }
            }

            await CollectData("日", "日检", dayStart, dayEnd);
            await CollectData("周", "周检", monday, sunday);
            await CollectData("月", "月检", monthStart, monthEnd);

            return Ok(new { uninspectedList, abnormalList });
        }

        /// <summary>
        /// 统计指定频率在指定月份的设备点检情况
        /// 日点检只查当天，周点检查本周，月点检查整月
        /// </summary>
        private async Task<object> BuildFrequencyStats(string frequency, int year, int month, DateTime startDate, DateTime endDate)
        {
            // 拥有该频率模板的设备列表
            var devices = await _context.InspectionTemplates
                .Where(t => t.Frequency == frequency)
                .Select(t => t.DeviceModel)
                .Distinct()
                .ToListAsync();

            var totalDevices = devices.Count;
            if (totalDevices == 0)
            {
                return new
                {
                    totalDevices = 0,
                    inspectedDevices = 0,
                    uninspectedDevices = 0,
                    uninspectedDeviceModels = new List<string>(),
                    abnormalDevices = 0,
                    abnormalDeviceModels = new List<string>()
                };
            }

            // 按周期确定实际查询的时间范围
            var now = DateTime.Now;
            DateTime periodStart, periodEnd;
            if (frequency == "日")
            {
                var today = now.Date;
                if (today >= startDate && today <= endDate)
                {
                    periodStart = today;
                    periodEnd = today.AddDays(1).AddTicks(-1);
                }
                else
                {
                    periodStart = endDate.Date;
                    periodEnd = endDate.Date.AddDays(1).AddTicks(-1);
                }
            }
            else if (frequency == "周")
            {
                int dayOfWeek = (int)now.DayOfWeek;
                int diff = dayOfWeek == 0 ? 6 : dayOfWeek - 1;
                var monday = now.Date.AddDays(-diff);
                if (monday < startDate) monday = startDate;
                var sunday = monday.AddDays(7).AddTicks(-1);
                if (sunday > endDate) sunday = endDate;
                periodStart = monday;
                periodEnd = sunday;
            }
            else
            {
                periodStart = startDate;
                periodEnd = endDate;
            }

            // ===== 批量查询 1：所有设备的全部点检项目（一次 SQL，含 IsMandatory 标记） =====
            var allItems = await _context.InspectionTemplates
                .Where(t => devices.Contains(t.DeviceModel)
                            && t.Frequency == frequency)
                .Select(t => new { t.DeviceModel, t.ItemName, t.IsMandatory })
                .ToListAsync();

            // 按设备分组 — 全部项目
            var allItemsByDevice = allItems
                .GroupBy(t => t.DeviceModel)
                .ToDictionary(g => g.Key, g => g.ToList());

            // 按设备分组 — 仅必须项目
            var mandatoryByDevice = allItems
                .Where(t => t.IsMandatory)
                .GroupBy(t => t.DeviceModel)
                .ToDictionary(g => g.Key, g => g.Select(x => x.ItemName).ToHashSet());

            // ===== 批量查询 2：所有设备在周期内的记录（一次 SQL） =====
            var allRecords = await _context.InspectionRecords
                .Where(r => devices.Contains(r.DeviceModel)
                            && r.Frequency == frequency
                            && r.InspectionTime >= periodStart
                            && r.InspectionTime <= periodEnd)
                .ToListAsync();

            // 按设备分组
            var recordsByDevice = allRecords
                .GroupBy(r => r.DeviceModel)
                .ToDictionary(g => g.Key, g => g.ToList());

            // ===== 批量查询 3：所有记录的结果明细（一次 SQL） =====
            var allRecordIds = allRecords.Select(r => r.Id).ToList();
            List<InspectionResult> allResults;
            if (allRecordIds.Count > 0)
            {
                // 分批处理 IN 子句（防止 ID 过多导致 SQL 过大）
                allResults = new List<InspectionResult>();
                const int batchSize = 500;
                for (int i = 0; i < allRecordIds.Count; i += batchSize)
                {
                    var batch = allRecordIds.Skip(i).Take(batchSize).ToList();
                    var batchResults = await _context.InspectionResults
                        .Where(res => batch.Contains(res.RecordId))
                        .ToListAsync();
                    allResults.AddRange(batchResults);
                }
            }
            else
            {
                allResults = new List<InspectionResult>();
            }

            // 按 recordId 分组
            var resultsByRecordId = allResults
                .GroupBy(res => res.RecordId)
                .ToDictionary(g => g.Key, g => g.ToList());

            // ===== 内存中按设备统计 =====
            var inspectedCount = 0;
            var abnormalCount = 0;
            var uninspectedDevices = new List<string>();
            var abnormalDeviceModels = new List<string>();

            foreach (var device in devices)
            {
                // 获取该设备的全部项目（含必须+选择）
                if (!allItemsByDevice.TryGetValue(device, out var deviceAllItems) || deviceAllItems.Count == 0)
                    continue;

                bool hasMandatory = mandatoryByDevice.TryGetValue(device, out var mandatorySet) && mandatorySet.Count > 0;

                // 获取该设备在周期内的记录
                if (!recordsByDevice.TryGetValue(device, out var deviceRecords) || deviceRecords.Count == 0)
                {
                    // 必须点检设备无记录 → 未点检；纯选择点检设备无记录 → 不统计
                    if (hasMandatory)
                        uninspectedDevices.Add(device);
                    continue;
                }

                // 收集本周期所有记录的 result item_name + 异常标记
                var deviceResultItemNames = new HashSet<string>();
                var anyAbnormal = false;
                foreach (var record in deviceRecords)
                {
                    if (resultsByRecordId.TryGetValue(record.Id, out var results))
                    {
                        foreach (var r in results)
                        {
                            deviceResultItemNames.Add(r.ItemName);
                            if (!r.IsNormal) anyAbnormal = true;
                        }
                    }
                }

                // 异常检查 — 所有设备（必须+选择）都计入异常
                if (anyAbnormal)
                {
                    abnormalCount++;
                    abnormalDeviceModels.Add(device);
                    continue;
                }

                if (hasMandatory)
                {
                    // 必须点检设备：检查是否所有必须项都已覆盖
                    var allMandatoryInspected = mandatorySet.All(item => deviceResultItemNames.Contains(item));
                    if (!allMandatoryInspected)
                        uninspectedDevices.Add(device);
                    else
                        inspectedCount++;
                }
                else
                {
                    // 纯选择点检设备，有记录且无异常 → 计入已点检
                    inspectedCount++;
                }
            }

            var uninspectedCount = uninspectedDevices.Count;

            return new
            {
                totalDevices,
                inspectedDevices = inspectedCount,
                uninspectedDevices = uninspectedCount,
                uninspectedDeviceModels = uninspectedDevices,
                abnormalDevices = abnormalCount,
                abnormalDeviceModels
            };
        }

        // 14. 月度摘要：返回所有设备的点检状态（已检/未检）
        [HttpGet("monthly-summary")]
        public async Task<IActionResult> GetMonthlySummary(int year, int month)
        {
            try
            {
                var startDate = new DateTime(year, month, 1);
                var endDate = startDate.AddMonths(1).AddTicks(-1);

                // 所有设备型号（从模板表去重）
                var allDevices = await _context.InspectionTemplates
                    .Select(t => t.DeviceModel)
                    .Distinct()
                    .ToListAsync();

                // 当月有记录的设备型号
                var inspectedDevices = await _context.InspectionRecords
                    .Where(r => r.InspectionTime >= startDate && r.InspectionTime <= endDate)
                    .Select(r => r.DeviceModel)
                    .Distinct()
                    .ToListAsync();

                var inspectedSet = new HashSet<string>(inspectedDevices);

                // 当月有异常（×）的设备 — 仅统计最后一次点检仍有异常的
                // 先取每台设备当月最新的记录 ID
                var latestRecordIds = await _context.InspectionRecords
                    .Where(r => r.InspectionTime >= startDate && r.InspectionTime <= endDate)
                    .GroupBy(r => r.DeviceModel)
                    .Select(g => g.OrderByDescending(r => r.InspectionTime).First().Id)
                    .ToListAsync();

                var abnormalDevices = await (
                    from r in _context.InspectionRecords
                    join res in _context.InspectionResults on r.Id equals res.RecordId
                    where latestRecordIds.Contains(r.Id) && res.ResultValue == "异常"
                    select r.DeviceModel
                ).Distinct().ToListAsync();

                var abnormalSet = new HashSet<string>(abnormalDevices);

                var devices = allDevices.Select(d => new
                {
                    deviceModel = d,
                    status = inspectedSet.Contains(d) ? "inspected" : "uninspected",
                    isAbnormal = abnormalSet.Contains(d)
                }).ToList();

                return Ok(new
                {
                    year,
                    month,
                    totalDevices = allDevices.Count,
                    inspectedDevices = inspectedSet.Count,
                    uninspectedDevices = allDevices.Count - inspectedSet.Count,
                    abnormalDevices = abnormalDevices.Count,
                    abnormalDeviceModels = abnormalDevices,
                    devices
                });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { error = ex.Message });
            }
        }

        // 获取当月完全未点检的设备清单（所有频率下均无记录）
        [HttpGet("uninspected-monthly")]
        public async Task<IActionResult> GetUninspectedMonthly(int year, int month)
        {
            try
            {
                var monthStart = new DateTime(year, month, 1);
                var monthEnd = monthStart.AddMonths(1).AddTicks(-1);

                // 所有有模板的设备型号
                var allDeviceModels = await _context.InspectionTemplates
                    .Select(t => t.DeviceModel)
                    .Distinct()
                    .ToListAsync();

                // 当月有任意记录的设备型号
                var inspectedModels = await _context.InspectionRecords
                    .Where(r => r.InspectionTime >= monthStart
                             && r.InspectionTime <= monthEnd)
                    .Select(r => r.DeviceModel)
                    .Distinct()
                    .ToListAsync();

                var inspectedSet = new HashSet<string>(inspectedModels);

                // 当月完全无记录的设备
                var uninspectedModels = allDeviceModels
                    .Where(m => !inspectedSet.Contains(m))
                    .ToList();

                // JOIN devices 获取 location
                var uninspectedDevices = new List<UninspectedDeviceItem>();
                if (uninspectedModels.Count > 0)
                {
                    uninspectedDevices = await _context.Devices
                        .Where(d => uninspectedModels.Contains(d.DeviceModel))
                        .Select(d => new UninspectedDeviceItem
                        {
                            DeviceModel = d.DeviceModel,
                            DeviceName = d.DeviceName,
                            DeviceLocation = d.DeviceLocation ?? ""
                        })
                        .OrderBy(d => d.DeviceLocation)
                        .ThenBy(d => d.DeviceModel)
                        .ToListAsync();
                }

                return Ok(new UninspectedMonthlyResponse
                {
                    Year = year,
                    Month = month,
                    TotalDevices = allDeviceModels.Count,
                    UninspectedCount = uninspectedDevices.Count,
                    UninspectedDevices = uninspectedDevices
                });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { error = ex.Message });
            }
        }

        // 15. 导入计划：上传 Excel，提取机种编码，匹配 dongtai 设备并更新 is_mandatory
        /// <summary>
        /// 从 Excel 计划文件中提取机种编码（5位：1字母+4数字），
        /// 匹配 device_location 同时包含机种编码和 "dongtai" 的设备，
        /// 将其 inspection_templates 的 is_mandatory 设为 1（必须点检）。
        /// 导入前所有 dongtai 设备先重置为 0（选择点检）。
        /// </summary>
        [HttpPost("import-plan")]
        [RequestSizeLimit(10_000_000)]
        public async Task<IActionResult> ImportPlan(IFormFile file)
        {
            if (file == null || file.Length == 0)
                return BadRequest(new { success = false, message = "请上传Excel文件" });

            if (!file.FileName.EndsWith(".xlsx", StringComparison.OrdinalIgnoreCase))
                return BadRequest(new { success = false, message = "仅支持 .xlsx 格式的Excel文件" });

            // 1. 解析 Excel — 提取所有符合 [A-Za-z]\d{4} 的机种编码
            //    使用 lookbehind/lookahead 确保 5 位编码是独立 token，
            //    避免从 "ABC12345" 中误提取 "C1234"
            var modelCodesFromExcel = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var extractRegex = new Regex(@"(?<![A-Za-z0-9])[A-Za-z]\d{4}(?![A-Za-z0-9])", RegexOptions.Compiled);

            using (var stream = file.OpenReadStream())
            using (var workbook = new ClosedXML.Excel.XLWorkbook(stream))
            {
                var ws = workbook.Worksheet(1);
                foreach (var cell in ws.CellsUsed())
                {
                    var text = cell.GetString()?.Trim();
                    if (string.IsNullOrEmpty(text)) continue;

                    var matches = extractRegex.Matches(text);
                    foreach (Match m in matches)
                        modelCodesFromExcel.Add(m.Value);
                }
            }

            int foundInExcel = modelCodesFromExcel.Count;
            if (foundInExcel == 0)
            {
                return Ok(new
                {
                    success = true,
                    message = "Excel 中未找到任何机种编码（格式：1字母+4数字）",
                    summary = new ImportPlanSummary
                    {
                        ExcelModelsFound = 0,
                        DongtaiDevicesTotal = 0,
                        DevicesMatched = 0,
                        TemplatesUpdated = 0
                    }
                });
            }

            // 2. 查询所有 device_location 包含 "dongtai" 的设备（不区分大小写）
            var allDongtaiDevices = await _context.Devices
                .Where(d => d.DeviceLocation != null
                         && d.DeviceLocation.ToLower().Contains("dongtai"))
                .ToListAsync();

            int dongtaiTotal = allDongtaiDevices.Count;

            // 3. 在内存中匹配：从 device_location 中提取所有符合格式的编码，
            //    然后与 Excel 编码做精确集合比对，避免子串误匹配
            //    （例如 "A1234" 不应匹配 location 中 "A12345" 的一部分）
            var matchedDeviceModels = new List<string>();
            foreach (var device in allDongtaiDevices)
            {
                var loc = device.DeviceLocation!;
                var codesInLocation = extractRegex.Matches(loc)
                    .Select(m => m.Value);
                if (codesInLocation.Any(c => modelCodesFromExcel.Contains(c)))
                {
                    matchedDeviceModels.Add(device.DeviceModel);
                }
            }

            int matchedCount = matchedDeviceModels.Count;

            // 4. 事务包裹：预处理 + 应用计划
            using var transaction = await _context.Database.BeginTransactionAsync();
            try
            {
                // 预处理：所有 dongtai 设备 → is_mandatory = 0（选择点检）
                if (dongtaiTotal > 0)
                {
                    var allDongtaiModels = allDongtaiDevices.Select(d => d.DeviceModel).ToList();
                    await _context.InspectionTemplates
                        .Where(t => allDongtaiModels.Contains(t.DeviceModel)
                                 && new[] { "日", "周", "月" }.Contains(t.Frequency))
                        .ExecuteUpdateAsync(s => s.SetProperty(t => t.IsMandatory, false));
                }

                // 应用计划：匹配成功的 dongtai 设备 → is_mandatory = 1（必须点检）
                int templatesUpdated = 0;
                if (matchedCount > 0)
                {
                    templatesUpdated = await _context.InspectionTemplates
                        .Where(t => matchedDeviceModels.Contains(t.DeviceModel)
                                 && new[] { "日", "周", "月" }.Contains(t.Frequency))
                        .ExecuteUpdateAsync(s => s.SetProperty(t => t.IsMandatory, true));
                }

                await transaction.CommitAsync();

                return Ok(new
                {
                    success = true,
                    message = $"导入完成：找到 {foundInExcel} 个机种编码，" +
                              $"动泰设备共 {dongtaiTotal} 台，匹配 {matchedCount} 台，" +
                              $"更新 {templatesUpdated} 条点检模板",
                    summary = new ImportPlanSummary
                    {
                        ExcelModelsFound = foundInExcel,
                        DongtaiDevicesTotal = dongtaiTotal,
                        DevicesMatched = matchedCount,
                        TemplatesUpdated = templatesUpdated
                    }
                });
            }
            catch (Exception ex)
            {
                await transaction.RollbackAsync();
                return StatusCode(500, new { success = false, message = "导入失败：" + ex.Message });
            }
        }


        // ===== 辅助方法 =====

        /// <summary>
        /// 根据频率和日期生成周期键
        /// 日: yyyy-MM-dd, 周: yyyy-Www (ISO 8601), 月: yyyy-MM
        /// </summary>
        private static string GeneratePeriodKey(string frequency, DateTime date)
        {
            return frequency switch
            {
                "日" => date.ToString("yyyy-MM-dd"),
                "周" => $"{date.Year:0000}-W{System.Globalization.CultureInfo.InvariantCulture.Calendar.GetWeekOfYear(date, System.Globalization.CalendarWeekRule.FirstFourDayWeek, DayOfWeek.Monday):00}",
                "月" => date.ToString("yyyy-MM"),
                _ => date.ToString("yyyy-MM-dd")
            };
        }




    }




    // 请求模型

    public class InspectionRequest
    {
        public string EmployeeId { get; set; }
        public string DeviceName { get; set; }
        public List<CheckItem> CheckItems { get; set; }
    }

    public class CheckItem
    {
        public string ItemName { get; set; }
        public string Result { get; set; }
        public string Remark { get; set; }
    }

    public class FullInspectionRequest
    {
        public string EmployeeId { get; set; }
        public string DeviceModel { get; set; }
        public List<InspectionResultItem> Results { get; set; }
        public string Frequency { get; set; } = "日";
    }

    public class InspectionResultItem
    {
        public string ItemName { get; set; }
        public string ResultValue { get; set; }
        public bool IsNormal { get; set; }
        public string Remark { get; set; }
    }
    // 临时 DTO 用于数据传输
    public class MonthlyRecordDto
    {
        public int RecordId { get; set; }
        public string ItemName { get; set; } = string.Empty;
        public int InspectionDay { get; set; }
        public string ResultValue { get; set; } = string.Empty;
        public string Remark { get; set; } = string.Empty;
        public bool IsNormal { get; set; }
        public string Status { get; set; } = string.Empty;
    }


    public class SaveDailyRecordRequest
    {
        public string EmployeeId { get; set; } = string.Empty;
        public string DeviceModel { get; set; } = string.Empty;
        public DateTime InspectionMonth { get; set; }
        public string Frequency { get; set; } = "日";
        public List<SaveRecordItem> Results { get; set; } = new();
    }

    public class SaveRecordItem
    {
        public int Day { get; set; }
        public string ItemName { get; set; } = string.Empty;
        public string ResultValue { get; set; } = string.Empty;
        public bool IsNormal { get; set; }
        public string Remark { get; set; } = string.Empty;
    }
    public class SaveSignaturesRequest
    {
        public string DeviceModel { get; set; } = string.Empty;
        public int Year { get; set; }
        public int Month { get; set; }
        public string Approver { get; set; } = string.Empty;
        public string Confirmer { get; set; } = string.Empty;
        public string OperatorName { get; set; } = string.Empty;  // 修改这里
    }

    public class LoginRequest
    {
        public string Username { get; set; } = string.Empty;
        public string Password { get; set; } = string.Empty;
    }

    public class AutoSignRequest
    {
        public string DeviceModel { get; set; } = string.Empty;
        public int Year { get; set; }
        public int Month { get; set; }
        public string Role { get; set; } = string.Empty;
        public string FullName { get; set; } = string.Empty;
    }

    public class AddOperatorRequest
    {
        public string EmployeeId { get; set; } = string.Empty;
        public string LastName { get; set; } = string.Empty;
    }

    public class ImportPlanSummary
    {
        /// <summary>Excel 中找到的机种编码数量</summary>
        public int ExcelModelsFound { get; set; }

        /// <summary>动泰设备总数（device_location 含 "dongtai"）</summary>
        public int DongtaiDevicesTotal { get; set; }

        /// <summary>匹配成功的设备数（location 含机种编码 + dongtai）</summary>
        public int DevicesMatched { get; set; }

        /// <summary>更新的 inspection_templates 条数</summary>
        public int TemplatesUpdated { get; set; }
    }

    // 用于 SqlQueryRaw 的临时映射类
    public class DailyOperatorRaw
    {
        public int Day { get; set; }
        public string EmployeeId { get; set; } = string.Empty;
    }

    public class UninspectedMonthlyResponse
    {
        public int Year { get; set; }
        public int Month { get; set; }
        public int TotalDevices { get; set; }
        public int UninspectedCount { get; set; }
        public List<UninspectedDeviceItem> UninspectedDevices { get; set; } = new();
    }

    public class UninspectedDeviceItem
    {
        public string DeviceModel { get; set; } = string.Empty;
        public string DeviceName { get; set; } = string.Empty;
        public string DeviceLocation { get; set; } = string.Empty;
    }

}