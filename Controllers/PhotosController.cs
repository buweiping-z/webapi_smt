using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using SkiaSharp;
using webapi.Data;
using webapi.Models;
using webapi.Services;

namespace webapi.Controllers
{
    [ApiController]
    [Route("api/inspection/photos")]
    public class PhotosController : ControllerBase
    {
        private readonly AppDbContext _context;
        private readonly IWebHostEnvironment _env;

        public PhotosController(AppDbContext context, IWebHostEnvironment env)
        {
            _context = context;
            _env = env;
        }

        /// <summary>
        /// 上传照片 — multipart/form-data，字段：recordId, itemName, photoOrder(可选), uploadedBy(可选)
        /// </summary>
        [HttpPost("upload")]
        [RequestSizeLimit(10_485_760)] // 10MB
        public async Task<IActionResult> UploadPhoto(
            IFormFile file,
            [FromForm] int recordId,
            [FromForm] string itemName,
            [FromForm] int photoOrder = 0,
            [FromForm] string uploadedBy = "")
        {
            if (file == null || file.Length == 0)
                return BadRequest(new { success = false, message = "请选择照片文件" });

            // === 文件大小校验 ===
            if (file.Length > 10_485_760)
                return BadRequest(new { success = false, message = "照片文件不能超过10MB" });

            // === MIME 类型白名单 ===
            var allowedMimeTypes = new[] { "image/jpeg", "image/png", "image/webp" };
            if (!allowedMimeTypes.Contains(file.ContentType.ToLower()))
                return BadRequest(new { success = false, message = "仅支持 JPEG / PNG / WebP 格式" });

            // === 魔数校验 ===
            using var ms = new MemoryStream();
            await file.CopyToAsync(ms);
            ms.Position = 0;

            var magicBytes = new byte[4];
            ms.Read(magicBytes, 0, 4);
            ms.Position = 0;

            bool validMagic = false;
            // JPEG: FF D8 FF
            if (magicBytes[0] == 0xFF && magicBytes[1] == 0xD8 && magicBytes[2] == 0xFF)
                validMagic = true;
            // PNG: 89 50 4E 47
            if (magicBytes[0] == 0x89 && magicBytes[1] == 0x50 && magicBytes[2] == 0x4E && magicBytes[3] == 0x47)
                validMagic = true;
            // WebP: 52 49 46 46 (RIFF)
            if (magicBytes[0] == 0x52 && magicBytes[1] == 0x49 && magicBytes[2] == 0x46 && magicBytes[3] == 0x46)
                validMagic = true;

            if (!validMagic)
                return BadRequest(new { success = false, message = "文件格式校验失败，请上传真实的 JPEG/PNG/WebP 图片" });

            // === 验证 record 存在 ===
            var record = await _context.InspectionRecords.FindAsync(recordId);
            if (record == null)
                return NotFound(new { success = false, message = $"点检记录 {recordId} 不存在" });

            // === 图片尺寸校验 ===
            try
            {
                using var tempMs = new MemoryStream(ms.ToArray());
                using var codec = SKCodec.Create(tempMs);
                if (codec.Info.Width < 200 || codec.Info.Height < 200)
                    return BadRequest(new { success = false, message = "图片尺寸过小，至少需要 200×200px" });
            }
            catch
            {
                return BadRequest(new { success = false, message = "无法解析图片，文件可能已损坏" });
            }

            // === 缩略图生成 + 原图缩放 ===
            ms.Position = 0;
            SKBitmap? originalBitmap;
            try
            {
                originalBitmap = SKBitmap.Decode(ms);
            }
            catch (Exception)
            {
                return BadRequest(new { success = false, message = "无法解码图片，文件可能已损坏" });
            }
            if (originalBitmap == null)
                return BadRequest(new { success = false, message = "无法解码图片" });

            var now = DateTime.Now;
            var yearMonth = now.ToString("yyyy/MM");
            var photosRoot = Path.Combine(_env.WebRootPath, "photos", yearMonth, recordId.ToString());
            Directory.CreateDirectory(photosRoot);

            // 先插入数据库记录获取 photoId
            var photo = new InspectionPhoto
            {
                RecordId = recordId,
                ItemName = itemName,
                PhotoPath = "",  // 先占位，等拿到 ID 后更新
                ThumbnailPath = "",
                PhotoOrder = photoOrder,
                UploadedBy = uploadedBy,
                CreatedAt = now
            };
            _context.InspectionPhotos.Add(photo);
            // 需要 SaveChanges 以获取自增 ID，但使用事务保护
            using var transaction = await _context.Database.BeginTransactionAsync();
            try
            {
                await _context.SaveChangesAsync();

                var photoFileName = $"{photo.Id}.jpg";
                var thumbFileName = $"{photo.Id}_thumb.jpg";

                // 原图缩放至最大宽度 1920px
                using var resizedBitmap = ResizeToMaxWidth(originalBitmap, 1920);
                var photoFilePath = Path.Combine(photosRoot, photoFileName);
                using (var fs = new FileStream(photoFilePath, FileMode.Create))
                {
                    using var data = resizedBitmap.Encode(SKEncodedImageFormat.Jpeg, 85);
                    data.SaveTo(fs);
                }

                // 生成 300×300 居中裁切缩略图
                using var thumbBitmap = CropCenter(resizedBitmap, 300, 300);
                var thumbFilePath = Path.Combine(photosRoot, thumbFileName);
                using (var fs = new FileStream(thumbFilePath, FileMode.Create))
                {
                    using var data = thumbBitmap.Encode(SKEncodedImageFormat.Jpeg, 85);
                    data.SaveTo(fs);
                }

                // 更新路径
                var relativePhotoPath = $"/photos/{yearMonth}/{recordId}/{photoFileName}";
                var relativeThumbPath = $"/photos/{yearMonth}/{recordId}/{thumbFileName}";
                photo.PhotoPath = relativePhotoPath;
                photo.ThumbnailPath = relativeThumbPath;
                await _context.SaveChangesAsync();

                await transaction.CommitAsync();

                // === 阶段2: 重新评估 record 的照片完整性 ===
                await ReEvaluateRecordStatus(recordId, record.DeviceModel);

                return Ok(new
                {
                    success = true,
                    photoId = photo.Id,
                    photoPath = relativePhotoPath,
                    thumbnailPath = relativeThumbPath
                });
            }
            catch
            {
                await transaction.RollbackAsync();
                // 清理可能已写入的文件
                var photoFile = Path.Combine(photosRoot, $"{photo.Id}.jpg");
                var thumbFile = Path.Combine(photosRoot, $"{photo.Id}_thumb.jpg");
                if (System.IO.File.Exists(photoFile)) System.IO.File.Delete(photoFile);
                if (System.IO.File.Exists(thumbFile)) System.IO.File.Delete(thumbFile);
                throw;
            }
        }

        /// <summary>
        /// 获取当月某设备的所有照片（用于月视图批量加载）
        /// </summary>
        [HttpGet("monthly")]
        public async Task<IActionResult> GetMonthlyPhotos(
            string deviceModel, int year, int month)
        {
            var startDate = new DateTime(year, month, 1);
            var endDate = startDate.AddMonths(1).AddTicks(-1);

            // JOIN 查询 — 避免 N+1
            var photos = await (
                from p in _context.InspectionPhotos
                join r in _context.InspectionRecords on p.RecordId equals r.Id
                where r.DeviceModel == deviceModel
                      && r.InspectionTime >= startDate
                      && r.InspectionTime <= endDate
                orderby p.CreatedAt descending
                select new
                {
                    p.Id,
                    p.RecordId,
                    p.ItemName,
                    p.PhotoPath,
                    p.ThumbnailPath,
                    p.PhotoOrder,
                    p.UploadedBy,
                    p.CreatedAt
                }
            ).ToListAsync();

            return Ok(photos);
        }

        /// <summary>
        /// 获取某条记录的所有照片
        /// </summary>
        [HttpGet("by-record/{recordId}")]
        public async Task<IActionResult> GetPhotosByRecord(int recordId)
        {
            var photos = await _context.InspectionPhotos
                .Where(p => p.RecordId == recordId)
                .OrderBy(p => p.PhotoOrder)
                .ThenBy(p => p.CreatedAt)
                .Select(p => new
                {
                    p.Id,
                    p.RecordId,
                    p.ItemName,
                    p.PhotoPath,
                    p.ThumbnailPath,
                    p.PhotoOrder,
                    p.UploadedBy,
                    p.CreatedAt
                })
                .ToListAsync();

            return Ok(photos);
        }

        /// <summary>
        /// 获取某条记录某个检查项的所有照片
        /// </summary>
        [HttpGet("by-item")]
        public async Task<IActionResult> GetPhotosByItem(
            [FromQuery] int recordId,
            [FromQuery] string itemName)
        {
            var photos = await _context.InspectionPhotos
                .Where(p => p.RecordId == recordId && p.ItemName == itemName)
                .OrderBy(p => p.PhotoOrder)
                .Select(p => new
                {
                    p.Id,
                    p.RecordId,
                    p.ItemName,
                    p.PhotoPath,
                    p.ThumbnailPath,
                    p.PhotoOrder,
                    p.CreatedAt
                })
                .ToListAsync();

            return Ok(photos);
        }

        /// <summary>
        /// 删除单张照片（仅上传者或管理员可删除）
        /// </summary>
        [HttpDelete("{photoId}")]
        public async Task<IActionResult> DeletePhoto(int photoId, [FromQuery] string? operatorId = null)
        {
            var photo = await _context.InspectionPhotos
                .AsTracking()
                .FirstOrDefaultAsync(p => p.Id == photoId);

            if (photo == null)
                return NotFound(new { success = false, message = "照片不存在" });

            // 权限校验：仅允许上传者删除自己的照片
            if (!string.IsNullOrEmpty(operatorId) && !string.IsNullOrEmpty(photo.UploadedBy)
                && !string.Equals(photo.UploadedBy, operatorId, StringComparison.OrdinalIgnoreCase))
                return BadRequest(new { success = false, message = "只能删除自己上传的照片" });

            // 删除物理文件
            var photosRoot = Path.Combine(_env.WebRootPath, "photos");
            var photoFullPath = Path.Combine(_env.WebRootPath, photo.PhotoPath.TrimStart('/'));
            var thumbFullPath = photo.ThumbnailPath != null
                ? Path.Combine(_env.WebRootPath, photo.ThumbnailPath.TrimStart('/'))
                : null;

            if (System.IO.File.Exists(photoFullPath))
                System.IO.File.Delete(photoFullPath);
            if (thumbFullPath != null && System.IO.File.Exists(thumbFullPath))
                System.IO.File.Delete(thumbFullPath);

            _context.InspectionPhotos.Remove(photo);
            await _context.SaveChangesAsync();

            return Ok(new { success = true, message = "照片已删除" });
        }

        /// <summary>
        /// 清理过期照片（手动触发）
        /// </summary>
        [HttpPost("cleanup")]
        public async Task<IActionResult> CleanupPhotos([FromQuery] int olderThanMonths = 6)
        {
            await PhotoCleanupService.CleanupPhotosAsync(_context, _env.WebRootPath, olderThanMonths);
            return Ok(new { success = true, message = $"已清理 {olderThanMonths} 个月前的照片" });
        }

        // ===== 辅助方法 =====

        /// <summary>
        /// 缩放图片至最大宽度，保持宽高比
        /// </summary>
        private static SKBitmap ResizeToMaxWidth(SKBitmap original, int maxWidth)
        {
            if (original.Width <= maxWidth)
                return original.Copy();

            float ratio = (float)maxWidth / original.Width;
            int newHeight = (int)(original.Height * ratio);
            var resized = original.Resize(new SKImageInfo(maxWidth, newHeight), new SKSamplingOptions(SKFilterMode.Linear, SKMipmapMode.Linear));
            return resized ?? original.Copy();
        }

        /// <summary>
        /// 居中裁切至目标尺寸
        /// </summary>
        private static SKBitmap CropCenter(SKBitmap source, int width, int height)
        {
            int cropX = Math.Max(0, (source.Width - width) / 2);
            int cropY = Math.Max(0, (source.Height - height) / 2);
            int cropW = Math.Min(width, source.Width);
            int cropH = Math.Min(height, source.Height);

            var rect = new SKRectI(cropX, cropY, cropX + cropW, cropY + cropH);
            var cropped = new SKBitmap(cropW, cropH);
            source.ExtractSubset(cropped, rect);

            // 如果裁切后小于目标尺寸，创建目标尺寸的画布居中放置
            if (cropW == width && cropH == height)
                return cropped;

            var canvas = new SKBitmap(width, height);
            using var g = new SKCanvas(canvas);
            g.Clear(SKColors.Black);
            g.DrawBitmap(cropped, (width - cropW) / 2, (height - cropH) / 2);
            cropped.Dispose();
            return canvas;
        }

        /// <summary>
        /// 重新评估 record 的照片完整性状态
        /// </summary>
        private async Task ReEvaluateRecordStatus(int recordId, string deviceModel)
        {
            var record = await _context.InspectionRecords
                .AsTracking()
                .FirstOrDefaultAsync(r => r.Id == recordId);

            if (record == null || record.Status != InspectionStatus.PendingPhoto)
                return;

            var requirePhotoItems = await _context.InspectionTemplates
                .Where(t => t.DeviceModel == deviceModel && t.RequirePhoto)
                .Select(t => t.ItemName)
                .ToListAsync();

            if (requirePhotoItems.Count == 0)
                return;

            var abnormalItems = await _context.InspectionResults
                .Where(r => r.RecordId == recordId && !r.IsNormal && requirePhotoItems.Contains(r.ItemName))
                .Select(r => r.ItemName)
                .ToListAsync();

            // 如果没有异常项，直接标记为 submitted
            if (abnormalItems.Count == 0)
            {
                record.Status = InspectionStatus.Submitted;
                await _context.SaveChangesAsync();
                return;
            }

            var photoItems = await _context.InspectionPhotos
                .Where(p => p.RecordId == recordId)
                .Select(p => p.ItemName)
                .Distinct()
                .ToListAsync();

            var allCovered = abnormalItems.All(item => photoItems.Contains(item));
            if (allCovered)
            {
                record.Status = InspectionStatus.Submitted;
                await _context.SaveChangesAsync();
            }
        }
    }
}
