# 点检定位照片功能 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为点检模板项添加定位照片功能，管理员 Web 后台上传，手机端自动展示缩略图并支持点击放大查看。

**Architecture:** 新建 `inspection_position_photos` 表与模板关联（多对一，每项最多3张）。模板 API 通过 `.Include()` 嵌入定位照片一次返回（1 次请求避免 N+1）。上传时用 SkiaSharp 生成 300×300 居中裁切缩略图。手机端 Coil 加载缩略图，Dialog 全屏查看原图。

**Tech Stack:** .NET 8.0 / EF Core 8.0 / SkiaSharp / Kotlin + Compose / Coil / MySQL

## Global Constraints

- 每模板项最多 3 张定位照片
- 上传文件仅允许 image/jpeg, image/png，单文件 ≤ 5MB
- 删除顺序：先删 DB 行，再删磁盘文件
- 前后端统一按 `photo_order ASC` 排序
- 项目无测试工程，使用 `dotnet build` + APK 编译验证

---

### Task 1: 数据库 Migration

**Files:**
- Create: `Migrations/<timestamp>_AddPositionPhotos.cs`（由 EF Core 工具生成）
- Modify: `Models/InspectionPositionPhoto.cs`（新建实体，Migration 依赖它）
- Modify: `Models/InspectionTemplate.cs`（新增导航属性）
- Modify: `Data/AppDbContext.cs`（新增 DbSet + Fluent API 配置）

**Interfaces:**
- Produces: 表 `inspection_position_photos` 存在于 MySQL，实体 `InspectionPositionPhoto` 可供其他 Task 使用

- [ ] **Step 1: 新建 `Models/InspectionPositionPhoto.cs`**

```csharp
using System.ComponentModel.DataAnnotations.Schema;

namespace webapi.Models
{
    [Table("inspection_position_photos")]
    public class InspectionPositionPhoto
    {
        [Column("id")]
        public int Id { get; set; }

        [Column("template_id")]
        public int TemplateId { get; set; }

        [Column("photo_path")]
        public string PhotoPath { get; set; } = string.Empty;

        [Column("thumbnail_path")]
        public string? ThumbnailPath { get; set; }

        [Column("photo_order")]
        public int PhotoOrder { get; set; } = 0;

        [Column("created_at")]
        public DateTime CreatedAt { get; set; }
    }
}
```

- [ ] **Step 2: 修改 `Models/InspectionTemplate.cs`，新增导航属性**

在原文件末尾 `}` 闭括号前添加：

```csharp
        [Column("position_photos")]
        public List<InspectionPositionPhoto> PositionPhotos { get; set; } = new();
```

注意：`[Column]` 标记仅用于文档，实际由 Fluent API 配置关系。

- [ ] **Step 3: 修改 `Data/AppDbContext.cs`**

新增 DbSet（在其他 DbSet 声明处添加）：

```csharp
public DbSet<InspectionPositionPhoto> InspectionPositionPhotos { get; set; }
```

在 `OnModelCreating` 末尾添加 Fluent 配置：

```csharp
modelBuilder.Entity<InspectionPositionPhoto>(entity =>
{
    entity.ToTable("inspection_position_photos");
    entity.HasKey(e => e.Id);
    entity.Property(e => e.Id).HasColumnName("id");
    entity.Property(e => e.TemplateId).HasColumnName("template_id").IsRequired();
    entity.Property(e => e.PhotoPath).HasColumnName("photo_path").HasMaxLength(500).IsRequired();
    entity.Property(e => e.ThumbnailPath).HasColumnName("thumbnail_path").HasMaxLength(500);
    entity.Property(e => e.PhotoOrder).HasColumnName("photo_order").HasDefaultValue(0);
    entity.Property(e => e.CreatedAt).HasColumnName("created_at").HasDefaultValueSql("CURRENT_TIMESTAMP");
    entity.HasIndex(e => e.TemplateId).HasDatabaseName("idx_position_photos_template");
});

// 在现有 InspectionTemplate 配置块内（entity.ToTable("inspection_templates") 之后）添加：
entity.HasMany(t => t.PositionPhotos)
    .WithOne()
    .HasForeignKey(p => p.TemplateId)
    .OnDelete(DeleteBehavior.Cascade);
```

- [ ] **Step 4: 生成 EF Core Migration**

```bash
dotnet ef migrations add AddPositionPhotos
```

- [ ] **Step 5: 应用 Migration**

```bash
dotnet ef database update
```

- [ ] **Step 6: Build 验证**

```bash
dotnet build
```

Expected: Build succeeded.

---

### Task 2: 增强 GET templates API 嵌入定位照片

**Files:**
- Modify: `Controllers/InspectionController.cs:66-80`（`GetTemplates` 方法）

**Interfaces:**
- Consumes: `InspectionTemplate.PositionPhotos` 导航属性（Task 1）, `InspectionPositionPhoto` 实体（Task 1）
- Produces: `GET api/inspection/templates/{deviceModel}` 返回的每个模板对象包含 `positionPhotos` 数组

- [ ] **Step 1: 修改 `GetTemplates` 方法，添加 `.Include()` 和排序**

```csharp
[HttpGet("templates/{deviceModel}")]
public async Task<IActionResult> GetTemplates(string deviceModel, [FromQuery] string? frequency = null)
{
    var query = _context.InspectionTemplates
        .Include(t => t.PositionPhotos)
        .Where(t => t.DeviceModel == deviceModel);

    if (!string.IsNullOrEmpty(frequency))
        query = query.Where(t => t.Frequency == frequency);

    var templates = await query
        .OrderBy(t => t.SortOrder)
        .ToListAsync();

    // 确保每个模板内的定位照片按 photoOrder 排序
    foreach (var t in templates)
    {
        t.PositionPhotos = t.PositionPhotos.OrderBy(p => p.PhotoOrder).ToList();
    }

    return Ok(templates);
}
```

- [ ] **Step 2: 确认 JSON 序列化无循环引用**

`InspectionPositionPhoto` 无反向导航属性（`Template`），不会产生循环引用。`System.Text.Json` 默认序列化 `PositionPhotos` 为 camelCase 数组。

- [ ] **Step 3: Build 验证**

```bash
dotnet build
```

Expected: Build succeeded.

- [ ] **Step 4: 可选 — 运行后通过 Swagger 验证**

```bash
dotnet run
```

访问 http://localhost:5039/swagger → GET `/api/inspection/templates/{deviceModel}` → 执行，确认响应 JSON 包含 `positionPhotos: []` 字段。

---

### Task 3: POST 上传定位照片 API

**Files:**
- Modify: `Controllers/InspectionController.cs`（新增端点）

**Interfaces:**
- Consumes: `IFormFile file`, `int photoOrder` 表单字段；`InspectionPositionPhoto` 实体（Task 1）
- Produces: `POST api/inspection/templates/{templateId}/position-photos` → 返回 `{ id, photoPath, thumbnailPath, photoOrder }`

- [ ] **Step 1: 在 `InspectionController` 中添加 POST 端点**

在 `InspectionController` 类的 `#region` 之后添加（先添加必要的 using）：

```csharp
using SkiaSharp;
```

```csharp
/// <summary>
/// 为指定模板项上传定位照片（每项最多3张）
/// </summary>
[HttpPost("templates/{templateId}/position-photos")]
[RequestSizeLimit(5_242_880)] // 5MB
public async Task<IActionResult> UploadPositionPhoto(
    int templateId,
    IFormFile file,
    [FromForm] int photoOrder = 0)
{
    // 1. 验证模板存在
    var template = await _context.InspectionTemplates
        .Include(t => t.PositionPhotos)
        .AsTracking()
        .FirstOrDefaultAsync(t => t.Id == templateId);

    if (template == null)
        return NotFound(new { success = false, message = $"模板项 {templateId} 不存在" });

    // 2. 检查数量上限
    if (template.PositionPhotos.Count >= 3)
        return BadRequest(new { success = false, message = "每个检查项最多上传3张定位照片" });

    if (file == null || file.Length == 0)
        return BadRequest(new { success = false, message = "请选择照片文件" });

    // 3. 文件大小校验
    if (file.Length > 5_242_880)
        return BadRequest(new { success = false, message = "照片文件不能超过5MB" });

    // 4. MIME 类型校验
    var allowedTypes = new[] { "image/jpeg", "image/png" };
    if (!allowedTypes.Contains(file.ContentType.ToLower()))
        return BadRequest(new { success = false, message = "仅支持 JPEG / PNG 格式" });

    // 5. 魔数校验
    using var ms = new MemoryStream();
    await file.CopyToAsync(ms);
    ms.Position = 0;

    var magic = new byte[4];
    ms.Read(magic, 0, 4);
    ms.Position = 0;

    bool validMagic = (magic[0] == 0xFF && magic[1] == 0xD8 && magic[2] == 0xFF)  // JPEG
                   || (magic[0] == 0x89 && magic[1] == 0x50 && magic[2] == 0x4E && magic[3] == 0x47); // PNG
    if (!validMagic)
        return BadRequest(new { success = false, message = "文件格式校验失败，请上传真实的 JPEG/PNG 图片" });

    // 6. 图片解码校验
    SKBitmap? bitmap;
    try
    {
        bitmap = SKBitmap.Decode(ms);
    }
    catch
    {
        return BadRequest(new { success = false, message = "无法解码图片，文件可能已损坏" });
    }
    if (bitmap == null)
        return BadRequest(new { success = false, message = "无法解码图片" });

    // 7. 保存文件
    var photosRoot = Path.Combine(_env.WebRootPath, "photos", "position", templateId.ToString());
    Directory.CreateDirectory(photosRoot);

    var photo = new InspectionPositionPhoto
    {
        TemplateId = templateId,
        PhotoPath = "",
        ThumbnailPath = "",
        PhotoOrder = photoOrder,
        CreatedAt = DateTime.Now
    };

    using var transaction = await _context.Database.BeginTransactionAsync();
    try
    {
        _context.InspectionPositionPhotos.Add(photo);
        await _context.SaveChangesAsync();

        var fileName = $"{photo.Id}.jpg";
        var thumbName = $"{photo.Id}_thumb.jpg";

        // 原图：最大宽度 1920px
        var maxWidth = 1920;
        var resized = bitmap.Width > maxWidth
            ? ResizeToMaxWidth(bitmap, maxWidth)
            : bitmap.Copy();

        var photoPath = Path.Combine(photosRoot, fileName);
        using (var fs = new FileStream(photoPath, FileMode.Create))
        {
            using var data = resized.Encode(SKEncodedImageFormat.Jpeg, 85);
            data.SaveTo(fs);
        }

        // 缩略图：300×300 居中裁切
        using var thumbBitmap = CropCenter(resized, 300, 300);
        var thumbPath = Path.Combine(photosRoot, thumbName);
        using (var fs = new FileStream(thumbPath, FileMode.Create))
        {
            using var data = thumbBitmap.Encode(SKEncodedImageFormat.Jpeg, 85);
            data.SaveTo(fs);
        }

        resized.Dispose();
        bitmap.Dispose();

        // 更新路径
        var relativePhotoPath = $"/photos/position/{templateId}/{fileName}";
        var relativeThumbPath = $"/photos/position/{templateId}/{thumbName}";
        photo.PhotoPath = relativePhotoPath;
        photo.ThumbnailPath = relativeThumbPath;
        await _context.SaveChangesAsync();

        await transaction.CommitAsync();

        return Ok(new
        {
            id = photo.Id,
            photoPath = photo.PhotoPath,
            thumbnailPath = photo.ThumbnailPath,
            photoOrder = photo.PhotoOrder
        });
    }
    catch
    {
        await transaction.RollbackAsync();
        // 清理可能已写的文件
        var pf = Path.Combine(photosRoot, $"{photo.Id}.jpg");
        var tf = Path.Combine(photosRoot, $"{photo.Id}_thumb.jpg");
        if (System.IO.File.Exists(pf)) System.IO.File.Delete(pf);
        if (System.IO.File.Exists(tf)) System.IO.File.Delete(tf);
        throw;
    }
}
```

- [ ] **Step 2: 复用 `PhotosController` 的辅助方法**

`ResizeToMaxWidth` 和 `CropCenter` 已在 `PhotosController` 中定义为 `private static`。由于不能跨 Controller 调用，需要在 `InspectionController` 中复制这两个方法。直接复制 `PhotosController.cs:315-349` 中的 `ResizeToMaxWidth` 和 `CropCenter` 两个 `private static` 方法到 `InspectionController` 的辅助方法区域。

```csharp
// 复制自 PhotosController — 放入 InspectionController 的辅助方法区域

private static SKBitmap ResizeToMaxWidth(SKBitmap original, int maxWidth)
{
    if (original.Width <= maxWidth)
        return original.Copy();

    float ratio = (float)maxWidth / original.Width;
    int newHeight = (int)(original.Height * ratio);
    var resized = original.Resize(new SKImageInfo(maxWidth, newHeight), new SKSamplingOptions(SKFilterMode.Linear, SKMipmapMode.Linear));
    return resized ?? original.Copy();
}

private static SKBitmap CropCenter(SKBitmap source, int width, int height)
{
    int cropX = Math.Max(0, (source.Width - width) / 2);
    int cropY = Math.Max(0, (source.Height - height) / 2);
    int cropW = Math.Min(width, source.Width);
    int cropH = Math.Min(height, source.Height);

    var rect = new SKRectI(cropX, cropY, cropX + cropW, cropY + cropH);
    var cropped = new SKBitmap(cropW, cropH);
    source.ExtractSubset(cropped, rect);

    if (cropW == width && cropH == height)
        return cropped;

    var canvas = new SKBitmap(width, height);
    using var g = new SKCanvas(canvas);
    g.Clear(SKColors.Black);
    g.DrawBitmap(cropped, (width - cropW) / 2, (height - cropH) / 2);
    cropped.Dispose();
    return canvas;
}
```

- [ ] **Step 3: Build 验证**

```bash
dotnet build
```

Expected: Build succeeded.

---

### Task 4: DELETE 删除定位照片 API

**Files:**
- Modify: `Controllers/InspectionController.cs`（新增端点）

**Interfaces:**
- Consumes: `photoId` 路径参数；`InspectionPositionPhoto` 实体（Task 1）
- Produces: `DELETE api/inspection/position-photos/{photoId}` → 删除 DB 行 + 磁盘文件

- [ ] **Step 1: 在 `InspectionController` 中添加 DELETE 端点**

```csharp
/// <summary>
/// 删除单张定位照片（先删 DB 行，再删磁盘文件）
/// </summary>
[HttpDelete("position-photos/{photoId}")]
public async Task<IActionResult> DeletePositionPhoto(int photoId)
{
    var photo = await _context.InspectionPositionPhotos
        .AsTracking()
        .FirstOrDefaultAsync(p => p.Id == photoId);

    if (photo == null)
        return NotFound(new { success = false, message = "照片不存在" });

    // 先删 DB 行
    _context.InspectionPositionPhotos.Remove(photo);
    await _context.SaveChangesAsync();

    // 再删磁盘文件（best effort，失败不影响 DB 结果）
    var photoFullPath = Path.Combine(_env.WebRootPath, photo.PhotoPath.TrimStart('/'));
    var thumbFullPath = photo.ThumbnailPath != null
        ? Path.Combine(_env.WebRootPath, photo.ThumbnailPath.TrimStart('/'))
        : null;

    if (System.IO.File.Exists(photoFullPath))
        System.IO.File.Delete(photoFullPath);
    if (thumbFullPath != null && System.IO.File.Exists(thumbFullPath))
        System.IO.File.Delete(thumbFullPath);

    return Ok(new { success = true, message = "定位照片已删除" });
}
```

- [ ] **Step 2: Build 验证**

```bash
dotnet build
```

Expected: Build succeeded.

---

### Task 5: Web 后台 — 定位照片管理模态框

**Files:**
- Modify: `html/index.html`（新增 HTML 模态框 + CSS + JS）

**Interfaces:**
- Consumes: `GET api/inspection/templates/{deviceModel}`（内嵌 positionPhotos）, `POST api/inspection/templates/{templateId}/position-photos`, `DELETE api/inspection/position-photos/{photoId}`（Task 2-4）
- Produces: 管理员可通过模态框为每个点检项管理定位照片

- [ ] **Step 1: 添加 CSS 样式**

在现有 `<style>` 标签内末尾 `</style>` 前添加：

```css
/* 定位照片管理模态框 */
.position-photo-modal { display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.5); z-index:10001; align-items:center; justify-content:center; }
.position-photo-modal.active { display:flex; }
.position-photo-content { background:var(--surface); border-radius:var(--radius-lg); padding:24px; max-width:600px; width:90%; max-height:80vh; overflow-y:auto; }
.position-photo-grid { display:flex; gap:10px; flex-wrap:wrap; margin-top:16px; }
.position-photo-item { position:relative; width:140px; height:140px; border-radius:var(--radius-sm); overflow:hidden; border:1px solid var(--border); }
.position-photo-item img { width:100%; height:100%; object-fit:cover; }
.position-photo-item .btn-delete { position:absolute; top:4px; right:4px; width:24px; height:24px; border-radius:50%; background:rgba(0,0,0,0.6); color:#fff; border:none; cursor:pointer; font-size:14px; line-height:24px; text-align:center; }
.position-photo-upload { width:140px; height:140px; border:2px dashed var(--border); border-radius:var(--radius-sm); display:flex; align-items:center; justify-content:center; cursor:pointer; color:var(--text-muted); font-size:32px; }
.position-photo-upload:hover { border-color:var(--accent); color:var(--accent); }
```

- [ ] **Step 2: 添加 HTML 模态框**

在 `</body>` 标签之前（所有模态框之后）添加：

```html
<!-- 定位照片管理模态框 -->
<div id="positionPhotoModal" class="position-photo-modal">
    <div class="position-photo-content">
        <h3 style="margin-bottom:16px;">📷 定位照片管理 — <span id="ppDeviceModel"></span></h3>
        <div>
            <label>选择点检项：</label>
            <select id="ppTemplateSelect" style="width:100%;padding:8px;margin-top:8px;border:1px solid var(--border);border-radius:var(--radius-sm);" onchange="onPositionPhotoTemplateChange()">
                <option value="">-- 请选择 --</option>
            </select>
        </div>
        <div id="ppPhotoGrid" class="position-photo-grid"></div>
        <div style="margin-top:20px;text-align:right;">
            <button class="btn btn-secondary" onclick="closePositionPhotoModal()">关闭</button>
        </div>
    </div>
</div>
<input type="file" id="ppFileInput" accept="image/jpeg,image/png" style="display:none;" onchange="uploadPositionPhoto(event)">
```

- [ ] **Step 3: 添加打开入口按钮**

在页面工具栏区域（与「一键导出」「导入计划」等按钮同行）添加：

```html
<button class="btn btn-secondary" onclick="openPositionPhotoModal()">📷 定位照片</button>
```

（查找现有按钮区域，添加入口。按钮 class 参考现有按钮样式。）

- [ ] **Step 4: 添加 JavaScript 逻辑**

在 `</script>` 标签之前添加：

```javascript
// ==================== 定位照片管理 ====================
let ppCurrentTemplateId = null;
let ppTemplates = [];

function openPositionPhotoModal() {
    if (!currentDevice) { showError('请先选择设备型号'); return; }
    document.getElementById('ppDeviceModel').textContent = currentDevice;

    // 用当前已加载的模板数据填充下拉框
    ppTemplates = templatesData || [];
    const sel = document.getElementById('ppTemplateSelect');
    sel.innerHTML = '<option value="">-- 请选择点检项 --</option>';
    ppTemplates.forEach(t => {
        const count = (t.positionPhotos || []).length;
        sel.innerHTML += `<option value="${t.id}">${t.itemName} (${count}/3)</option>`;
    });
    sel.value = '';

    document.getElementById('ppPhotoGrid').innerHTML = '';
    document.getElementById('positionPhotoModal').classList.add('active');
}

function closePositionPhotoModal() {
    document.getElementById('positionPhotoModal').classList.remove('active');
    ppCurrentTemplateId = null;
    document.getElementById('ppPhotoGrid').innerHTML = '';
}

function onPositionPhotoTemplateChange() {
    const templateId = parseInt(document.getElementById('ppTemplateSelect').value) || null;
    ppCurrentTemplateId = templateId;
    renderPositionPhotos();
}

function renderPositionPhotos() {
    const grid = document.getElementById('ppPhotoGrid');
    if (!ppCurrentTemplateId) { grid.innerHTML = ''; return; }

    const template = ppTemplates.find(t => t.id === ppCurrentTemplateId);
    if (!template) return;

    const photos = (template.positionPhotos || []).sort((a, b) => a.photoOrder - b.photoOrder);
    let html = '';

    photos.forEach(p => {
        html += `<div class="position-photo-item">
            <img src="${API_BASE_URL}${p.thumbnailPath}" alt="定位照片" onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%22140%22 height=%22140%22><rect fill=%22%23f1f5f9%22 width=%22140%22 height=%22140%22/><text x=%2270%22 y=%2270%22 text-anchor=%22middle%22 dy=%22.3em%22 fill=%22%2394a3b8%22 font-size=%2212%22>加载失败</text></svg>'">
            <button class="btn-delete" onclick="deletePositionPhoto(${p.id})">✕</button>
        </div>`;
    });

    if (photos.length < 3) {
        html += `<div class="position-photo-upload" onclick="document.getElementById('ppFileInput').click()">+</div>`;
    }

    grid.innerHTML = html;
}

async function uploadPositionPhoto(event) {
    const file = event.target.files[0];
    if (!file || !ppCurrentTemplateId) { event.target.value = ''; return; }

    if (file.size > 5 * 1024 * 1024) {
        showError('文件大小不能超过 5MB');
        event.target.value = '';
        return;
    }

    const formData = new FormData();
    formData.append('file', file);
    formData.append('photoOrder', 0);

    try {
        await axios.post(`${API_BASE_URL}/api/Inspection/templates/${ppCurrentTemplateId}/position-photos`, formData, {
            headers: { 'Content-Type': 'multipart/form-data' }
        });
        showStatus('定位照片上传成功');
        // 重新加载模板数据以获取最新 positionPhotos
        templatesData = await loadTemplates(currentDevice);
        // 更新下拉框选项
        onPositionPhotoTemplateChange();
        renderPositionPhotos();
    } catch (error) {
        showError('上传失败：' + (error.response?.data?.message || error.message));
    }
    event.target.value = '';
}

async function deletePositionPhoto(photoId) {
    if (!confirm('确认删除该定位照片？')) return;

    try {
        await axios.delete(`${API_BASE_URL}/api/Inspection/position-photos/${photoId}`);
        showStatus('定位照片已删除');
        // 刷新模板数据
        templatesData = await loadTemplates(currentDevice);
        onPositionPhotoTemplateChange();
        renderPositionPhotos();
    } catch (error) {
        showError('删除失败：' + (error.response?.data?.message || error.message));
    }
}
```

- [ ] **Step 5: 验证 — 启动前端服务**

```bash
cd html && python -m http.server 8080
```

Expected: 服务启动无 JS 语法错误（浏览器 Console 无报错）。

---

### Task 6: Android — 数据模型 + UI

**Files:**
- Modify: `machine_check/app/src/main/java/com/machine_check/inspection/data/models/Models.kt`（新增 `PositionPhoto` + 修改 `InspectionTemplate`）
- Modify: `machine_check/app/src/main/java/com/machine_check/inspection/data/network/RetrofitClient.kt`（公开 `baseUrl`）
- Modify: `machine_check/app/src/main/java/com/machine_check/inspection/ui/inspection/InspectionScreen.kt`（`InspectionItemCard` 新增定位照片区域）

**Interfaces:**
- Consumes: `InspectionTemplate.positionPhotos`（来自 API 响应）, `RetrofitClient.baseUrl`
- Produces: 点检表单中每条有定位照片的项展示缩略图，点击弹出全屏 Dialog 查看原图

- [ ] **Step 1: `Models.kt` — 新增 `PositionPhoto` data class**

在 `Models.kt` 末尾（`PhotoItemState` 之后）添加：

```kotlin
/**
 * 定位照片（模板级别的指示照片，非异常留证照片）
 */
data class PositionPhoto(
    val id: Int,
    val photoPath: String,
    val thumbnailPath: String?,
    val photoOrder: Int
)
```

- [ ] **Step 2: `Models.kt` — 修改 `InspectionTemplate`，新增字段**

在 `InspectionTemplate` data class 的 `requirePhoto` 字段之后添加：

```kotlin
    val positionPhotos: List<PositionPhoto> = emptyList()  // 定位指示照片列表
```

`InspectionTemplate` 最终：

```kotlin
data class InspectionTemplate(
    val id: Int,
    val deviceModel: String,
    val itemName: String,
    val itemType: String,
    val unit: String?,
    val normalMin: Double?,
    val normalMax: Double?,
    val sortOrder: Int,
    val frequency: String = "日",
    val requirePhoto: Boolean = false,
    val positionPhotos: List<PositionPhoto> = emptyList()
)
```

- [ ] **Step 3: `RetrofitClient.kt` — 公开 `baseUrl`**

在 `RetrofitClient` object 内添加公开属性：

```kotlin
val baseUrl: String get() = BASE_URL
```

放置位置：`BASE_URL` 声明之后。

- [ ] **Step 4: `InspectionScreen.kt` — 在 `InspectionItemCard` 中添加状态变量和全屏 Dialog**

首先，在 `InspectionItemCard` 函数开头（`val template = itemState.template` 之后）添加：

```kotlin
    val photoBaseUrl = RetrofitClient.baseUrl.trimEnd('/')
    var showFullScreenDialog by remember { mutableStateOf(false) }
    var fullScreenPhotoUrl by remember { mutableStateOf("") }
```

然后，在 `InspectionItemCard` 的 `Card` 闭合 `}` 之后、函数结尾之前（即 `InspectionItemCard` 函数体的最后）添加全屏 Dialog：

```kotlin

    // 全屏查看定位照片
    if (showFullScreenDialog) {
        Dialog(
            onDismissRequest = { showFullScreenDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showFullScreenDialog = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = fullScreenPhotoUrl,
                    contentDescription = "定位照片全屏",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
```

- [ ] **Step 5: `InspectionScreen.kt` — 在 `InspectionItemCard` 中添加定位照片缩略图区域**

在 `InspectionItemCard` 的备注输入框（`OutlinedTextField`）闭合 `)` 之后、现有照片区域的 `if (template.requirePhoto)` 之前插入：

```kotlin
            // ===== 定位照片区域 =====
            val positionPhotos = template.positionPhotos
                .filter { !it.thumbnailPath.isNullOrBlank() }
                .sortedBy { it.photoOrder }
            if (positionPhotos.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("📍 定位指示", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    positionPhotos.forEach { photo ->
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    // 全屏查看原图
                                    showFullScreenDialog = true
                                    fullScreenPhotoUrl = "${photoBaseUrl}/${photo.photoPath.trimStart('/')}"
                                }
                        ) {
                            AsyncImage(
                                model = "${photoBaseUrl}/${photo.thumbnailPath!!.trimStart('/')}",
                                contentDescription = "定位照片",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
```

- [ ] **Step 6: 检查并补充 import**

在 `InspectionScreen.kt` 文件顶部 import 区域，确认以下 import 已存在（缺失则添加）：

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.machine_check.inspection.data.network.RetrofitClient
```

（其中 `AsyncImage` 应已在现有 import 中；`Color`, `background`, `clickable` 可能也已存在。只需添加缺失的。）

- [ ] **Step 7: 编译验证**

```bash
cd machine_check && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

---

### Task 7: 端到端验证

- [ ] **Step 1: 启动后端**

```bash
dotnet run
```

- [ ] **Step 2: 启动 Web 前端**

```bash
cd html && python -m http.server 8080
```

- [ ] **Step 3: 通过 Swagger 测试**

1. 打开 http://localhost:5039/swagger
2. POST `/api/inspection/templates/{templateId}/position-photos` 上传测试图片
3. GET `/api/inspection/templates/{deviceModel}` 确认响应含 `positionPhotos`
4. DELETE `/api/inspection/position-photos/{photoId}` 确认删除成功

- [ ] **Step 4: Web 后台验证**

打开 http://localhost:8080，选择设备 → 点击「📷 定位照片」→ 选择点检项 → 上传 → 查看缩略图 → 删除 → 关闭。

- [ ] **Step 5: Android 验证**

安装编译的 APK，扫码进入点检 → 确认有定位照片的项在卡片内显示缩略图 → 点击放大全屏查看 → 点击空白处关闭。
