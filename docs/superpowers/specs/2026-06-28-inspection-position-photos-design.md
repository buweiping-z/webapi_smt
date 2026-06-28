# 点检定位照片功能设计

## 概述

在手机端点检时，对指定的点检项目展示**定位照片**（指示照片），帮助操作人员确认检查位置和角度。管理员在 Web 后台为需要的点检模板项上传定位照片，每项最多 3 张。

## 数据库

### 新表 `inspection_position_photos`

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | INT PK AUTO_INCREMENT | 主键 |
| `template_id` | INT FK → inspection_templates.id | 关联的点检模板项 |
| `photo_path` | VARCHAR(500) NOT NULL | 原图路径 |
| `thumbnail_path` | VARCHAR(500) | 缩略图路径 |
| `photo_order` | INT DEFAULT 0 | 排序 (0-2)，前后端统一 `ORDER BY photo_order ASC` |
| `created_at` | DATETIME DEFAULT NOW() | 创建时间 |

- 外键 `template_id` 级联删除：模板项删除时自动删除关联定位照片
- 索引：`(template_id)` 加速按模板查询

## 后端

### 新文件

- `Models/InspectionPositionPhoto.cs` — 实体类，`[Table("inspection_position_photos")]`

### 修改文件

- `Data/AppDbContext.cs` — 新增 `DbSet<InspectionPositionPhoto>` + Fluent API 配置表名、外键、级联删除、索引
- `Controllers/InspectionController.cs` — 增强现有模板接口 + 新增 2 个端点

### 模板接口增强（解决 N+1 问题）

**`GET api/inspection/templates/{deviceModel}`** — 后端通过 EF Core `.Include(t => t.PositionPhotos)` 将定位照片随模板一起返回，手机端 1 次请求即可获取全部数据，避免 20 个模板项发 20 个并发 HTTP 请求。

返回结构变化：
```json
[
  {
    "id": 1,
    "deviceModel": "XYZ-100",
    "itemName": "检查油压",
    "itemType": "normal_abnormal",
    ...
    "positionPhotos": [
      { "id": 10, "photoPath": "/photos/position/...", "thumbnailPath": "/photos/position/...", "photoOrder": 0 },
      { "id": 11, "photoPath": "/photos/position/...", "thumbnailPath": "/photos/position/...", "photoOrder": 1 }
    ]
  }
]
```

实现方式：
- `InspectionTemplate` 实体新增导航属性 `List<InspectionPositionPhoto> PositionPhotos`
- Fluent API 配置 `.HasMany(t => t.PositionPhotos).WithOne().HasForeignKey(p => p.TemplateId).OnDelete(DeleteBehavior.Cascade)`
- Controller 查询时 `.Include(t => t.PositionPhotos).OrderBy(t => t.SortOrder)`
- JSON 序列化时 `PositionPhotos` 自然输出到模板对象中
- 无定位照片的模板项返回空数组 `[]`

### 新增 API 端点

| 方法 | 路由 | 用途 |
|------|------|------|
| `POST` | `api/inspection/templates/{templateId}/position-photos` | 上传定位照片（multipart/form-data） |
| `DELETE` | `api/inspection/position-photos/{photoId}` | 删除单张定位照片 |

#### POST 上传细节

- 参数：`IFormFile file` + `int photoOrder`
- **文件校验**：
  - ContentType 仅允许 `image/jpeg`、`image/png`
  - 单文件大小限制 ≤ 5MB
- 校验该模板已有照片数 < 3，超限返回 400
- 保存到 `wwwroot/photos/position/{templateId}/` 目录
- 自动生成缩略图（复用现有 PhotosController 缩略逻辑）
- 返回新创建的 `InspectionPositionPhoto` 对象

#### DELETE 细节

- **先删 DB 行，再删磁盘文件**：即使删文件失败，DB 中已无记录，避免孤儿记录导致前端展示已删除的照片。磁盘孤儿文件可通过后续定时任务清理。
- 剩余照片的 `photoOrder` 不重排，前端按 `photo_order ASC` 排序展示

## Web 后台（`html/index.html`）

### 入口

在点检记录表格页面添加 **「📷 定位照片」按钮**，点击弹出模态框。

### 模态框布局

- 下拉框选择点检项（按 sortOrder 排序，标注已有照片数如 `检查油压 (2/3)`）
- 选中项后显示已有定位照片缩略图网格，按 `photoOrder` 升序排列，每张右上角 ✕ 删除按钮
- 上传时即时预览缩略图，帮助管理员确认照片质量
- 已有照片 < 3 张时显示 + 上传占位按钮
- 上传/删除即时生效，通过 API 直接操作

## Android 手机端

### 数据模型变更

- `Models.kt` 新增 `PositionPhoto` data class（id, photoPath, thumbnailPath, photoOrder）
- `InspectionTemplate` 新增 `positionPhotos: List<PositionPhoto>` 字段（直接来自模板 API 响应，无需额外请求）

### 数据加载（无 N+1）

- `InspectionViewModel.loadTemplates()` 调用 `getTemplates(deviceModel, frequency)`，模板 API 已内嵌定位照片
- 加载后模板中的 `positionPhotos` 直接流入 `InspectionItemState`

### UI

- `InspectionItemCard` 中，在备注输入框下方、现有异常拍照区域上方，新增定位照片缩略图行
- 使用 Coil `AsyncImage` 加载缩略图（`thumbnailPath`）
- 点击缩略图弹出全屏 `Dialog` 展示原图（`photoPath`）
- 无定位照片的项不显示该区域
- 区域标签：「📍 定位指示」
- 缩略图按 `photoOrder` 升序排列

### 离线/缓存说明

- Coil 默认使用 HTTP 缓存，首次加载后短时间内无网络也能从磁盘缓存读取
- 如需严格离线支持（如地下车间长时间无网），可在后续迭代中将定位照片预下载到本地沙盒并启用 Coil 的强磁盘缓存策略。当前版本不强制要求离线支持。

## 影响范围

| 层 | 文件 | 改动类型 |
|------|------|------|
| DB | Migration | 新建表 |
| 后端 | `Models/InspectionPositionPhoto.cs` | 新建 |
| 后端 | `Models/InspectionTemplate.cs` | 修改（新增导航属性 `PositionPhotos`） |
| 后端 | `Data/AppDbContext.cs` | 修改（新增 DbSet + Fluent 配置 + Include） |
| 后端 | `Controllers/InspectionController.cs` | 修改（增强 GET templates + 新增 2 个端点） |
| Web | `html/index.html` | 修改（新增模态框） |
| 手机 | `data/models/Models.kt` | 修改（新增 `PositionPhoto` + `InspectionTemplate` 加字段） |
| 手机 | `ui/inspection/InspectionScreen.kt` | 修改（`InspectionItemCard` 新增定位照片缩略图） |
