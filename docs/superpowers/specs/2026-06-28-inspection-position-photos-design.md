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
| `photo_order` | INT DEFAULT 0 | 排序 (0-2) |
| `created_at` | DATETIME DEFAULT NOW() | 创建时间 |

- 外键 `template_id` 级联删除：模板项删除时自动删除关联定位照片
- 索引：`(template_id)` 加速按模板查询

## 后端

### 新文件

- `Models/InspectionPositionPhoto.cs` — 实体类，`[Table("inspection_position_photos")]`

### 修改文件

- `Data/AppDbContext.cs` — 新增 `DbSet<InspectionPositionPhoto>` + Fluent API 配置表名、外键、级联删除、索引

### API 端点（全部在 `InspectionController` 中）

| 方法 | 路由 | 用途 |
|------|------|------|
| `GET` | `api/inspection/templates/{templateId}/position-photos` | 获取指定模板项的定位照片列表 |
| `POST` | `api/inspection/templates/{templateId}/position-photos` | 上传定位照片（multipart/form-data） |
| `DELETE` | `api/inspection/position-photos/{photoId}` | 删除单张定位照片 |

#### POST 上传细节

- 参数：`IFormFile file` + `int photoOrder`
- 校验该模板已有照片数 < 3，超限返回 400
- 保存到 `wwwroot/photos/position/{templateId}/` 目录
- 自动生成缩略图（复用现有 PhotosController 缩略逻辑）
- 返回新创建的 `InspectionPositionPhoto` 对象

#### DELETE 细节

- 查询记录 → 删除磁盘文件（原图 + 缩略图）→ 删除 DB 行
- 剩余照片的 `photoOrder` 不重排

## Web 后台（`html/index.html`）

### 入口

在点检记录表格页面添加 **「📷 定位照片」按钮**，点击弹出模态框。

### 模态框布局

- 下拉框选择点检项（按 sortOrder 排序，标注已有照片数如 `检查油压 (2/3)`）
- 选中项后显示已有定位照片缩略图网格，每张右上角 ✕ 删除按钮
- 已有照片 < 3 张时显示 + 上传占位按钮
- 上传/删除即时生效

## Android 手机端

### 数据模型变更

- `Models.kt` 新增 `PositionPhoto` data class（id, photoPath, thumbnailPath, photoOrder）
- `InspectionTemplate` 新增 `positionPhotos: List<PositionPhoto>` 字段
- `InspectionItemState` 新增 `positionPhotos: List<PositionPhoto>` 字段

### API

- `ApiService.kt` 新增 `getPositionPhotos(templateId)` 接口

### 数据加载

- `InspectionViewModel.loadTemplates()` 拿到模板列表后，并发请求每个模板项的定位照片，存入 `InspectionItemState`

### UI

- `InspectionItemCard` 中，在备注输入框下方、现有异常拍照区域上方，新增定位照片缩略图行
- 使用 Coil `AsyncImage` 加载缩略图（`thumbnailPath`）
- 点击缩略图弹出全屏 `Dialog` 展示原图（`photoPath`）
- 无定位照片的项不显示该区域
- 区域标签：「📍 定位指示」

## 影响范围

| 层 | 文件 | 改动类型 |
|------|------|------|
| DB | Migration | 新建表 |
| 后端 | `Models/InspectionPositionPhoto.cs` | 新建 |
| 后端 | `Data/AppDbContext.cs` | 修改（新增 DbSet + 配置） |
| 后端 | `Controllers/InspectionController.cs` | 修改（新增 3 个端点） |
| Web | `html/index.html` | 修改（新增模态框） |
| 手机 | `data/models/Models.kt` | 修改（新增类 + 字段） |
| 手机 | `data/network/ApiService.kt` | 修改（新增接口） |
| 手机 | `ui/inspection/InspectionViewModel.kt` | 修改（加载逻辑） |
| 手机 | `ui/inspection/InspectionScreen.kt` | 修改（UI 渲染） |
