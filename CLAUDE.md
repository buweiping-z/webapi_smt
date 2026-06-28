# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

设备点检管理系统 (Equipment Inspection Management System) — an ASP.NET Core 8.0 Web API backend with a vanilla HTML/JS frontend and an Android mobile app. The system manages inspection records, templates, results, signatures, and user authentication for equipment inspection workflows.

## Tech Stack

- **Backend**: .NET 8.0, ASP.NET Core Web API, Entity Framework Core 8.0
- **Database**: MySQL (Pomelo.EntityFrameworkCore.MySql 8.0.0)
- **API Docs**: Swashbuckle (Swagger) in Development mode
- **Frontend**: Single-page HTML served separately via `python -m http.server 8080` from the `html/` directory

## Build & Run

```bash
# Restore dependencies
dotnet restore

# Build
dotnet build

# Run (default profile, listens on http://localhost:5039)
dotnet run

# Run with watch (hot reload)
dotnet watch run

# Publish (output to publish/)
dotnet publish -c Release -o publish
```

The project has no test project. There is only one `.csproj` — `webapi.csproj`.

## Architecture

### Standard 3-layer MVC layout:

```
Controllers/          # API controllers (InspectionController, PhotosController)
Models/               # EF Core entity models mapped to MySQL tables
Data/                 # DbContext (AppDbContext)
html/                 # Frontend SPA — static HTML served separately
machine_check/        # Android 手机端 — Kotlin + Jetpack Compose（详见其自有 CLAUDE.md）
Program.cs            # App startup, DI registration, middleware pipeline
```

### Database (MySQL, database name: `InspectionDB`)

Connection string is in `appsettings.json` (no secrets manager used). All tables use snake_case naming.

**5 entity tables** (models in `Models/`, DbSets in `Data/AppDbContext.cs`):
- `inspection_records` — InspectionRecord: header record for each inspection submission
- `inspection_results` — InspectionResult: individual check items within a record (FK → inspection_records.id, cascade delete)
- `inspection_templates` — InspectionTemplate: per-device-model check item templates
- `inspection_signatures` — InspectionSignature: monthly signatures (approver, confirmer, operator) per device model
- `inspection_users` — InspectionUser: user accounts for login (plain-text passwords — not hashed)

### API Endpoints (all under `api/inspection`)

| Method | Route | Purpose |
|--------|-------|---------|
| POST | `submit` | Legacy simple inspection submission |
| GET | `templates/{deviceModel}` | Get check item templates for a device model |
| POST | `submit-full` | Full inspection with multiple result items |
| GET | `devices` | List distinct device models |
| GET | `records/monthly?deviceModel=&year=&month=` | Monthly grid records (values normalized: 正常→○, 异常→×) |
| POST | `records/save` | Save/update daily inspection records (upsert logic) |
| GET | `signatures/get?deviceModel=&year=&month=` | Get signatures for a month |
| POST | `signatures/save` | Save signatures (upsert) |
| GET | `users/list` | List users for dropdowns |
| POST | `users/login` | Login (plain-text password check) |
| POST | `signatures/auto-sign` | Auto-sign after login based on role |

### Key patterns
- **Request DTOs are defined inline** in `Controllers/InspectionController.cs` at the bottom of the file (not in separate files)
- **Model-to-table mapping** uses both `[Column("snake_case")]` data annotations on entities AND Fluent API in `OnModelCreating`
- **CORS**: Open (`AllowAll` policy — any origin, method, header)
- **Swagger**: Enabled only in Development; available at `/swagger`
- No authentication middleware — auth is done manually via the `users/login` endpoint
- **Navigation property + JSON serialization**: 向实体添加导航属性（如 `InspectionTemplate.PositionPhotos`）时，子实体不要加反向导航属性，否则 `System.Text.Json` 会循环引用。通过 `.Include()` 预加载，API 直接返回实体即可，JSON 会自然嵌套序列化。
- **`[Column]` 仅对标量属性有效**：导航属性不要加 `[Column]` 注解，EF Core 会忽略它，徒增误导。

### Frontend

The `html/index.html` is a standalone SPA (no build step, no framework). It calls the .NET API endpoints. Serve it with:
```bash
cd html && python -m http.server 8080
```
The frontend expects the API at the same host. In production the API and frontend should be on the same origin or CORS must remain open.

#### PDF 导出注意事项（`exportPDF()` / `batchExportAll()`）

使用 `html2pdf` 库（html2canvas + jsPDF），以下是多次修复后的关键经验：

1. **CSS 隔离**：html2pdf 将 HTML 字符串插入 DOM 临时元素后截图，页面所有 CSS（含 `@media` 响应式查询）都会影响渲染结果。最外层 div 必须设 `isolation:isolate` 并显式指定 `color`、`line-height` 等继承属性。
2. **z-index 层叠上下文**：`transform:translate()` 会创建新层叠上下文，导致 html2canvas 拍平时层叠顺序错乱。用 `display:flex` + `align-items/justify-content:center` 替代 transform 居中。所有 absolute 元素显式设 `z-index:0`，文字设 `z-index:2`。
3. **连续导出清理**：`save()` 后临时 DOM 元素可能残留（id 以 `html2pdf` 开头），批量导出时会累积。每次 save() 后延时清理 `[id^="html2pdf"]` 元素。
4. **强制 reflow**：DOM 更新后 `scrollWidth` 取值前调用 `getBoundingClientRect()` 强制浏览器完成布局。
5. **批量导出容错**：逐设备独立 try-catch，单个失败不中止，记录成功/失败列表并汇总。全局状态必须在 finally 块恢复。
6. **浏览器缩放兼容（关键）**：`html2pdf` 库内部会读取 `window.devicePixelRatio` 作为 html2canvas 默认 scale，浏览器 Ctrl+/- 缩放会改变 dpr 导致 canvas 尺寸漂移、PDF 内容偏移出界。**必须绕过 html2pdf**，直接调用 `html2canvas(container, { scale: 1, width: capW, windowWidth: capW, useCORS: true })` + 手动 `new jsPDF()` 拼 PDF。`scale: 1` 硬编码锁死、`windowWidth: capW` 固定虚拟视口，canvas 输出尺寸恒定，PDF 图片位置 `(marginLeft, marginTop, imgWidth, imgHeight)` 手动计算永不偏移。同时 `capW` 应从列结构估算（`fixedColsW + daysInMonth * 55`）而非 `table.scrollWidth` DOM 测量。
7. **CDN 依赖**：html2canvas@1.4.1 + jspdf@2.5.1（替代 html2pdf.js@0.10.1），本地 fallback 文件需预置。

### Android 手机端 (`machine_check/`)

`machine_check/` 是 Android 点检 App — Kotlin + Jetpack Compose + Material 3，用于工厂车间扫码点检。

- **核心流程**：扫码（工号 QR → 设备型号 QR）→ 从 API 拉模板动态生成表单 → 逐项填正常/异常 → 拍照（如需）→ 提交
- **API 地址**：模拟器用 `http://10.0.2.2:5039`，真机用主机局域网 IP
- **构建**：`cd machine_check && ./gradlew :app:assembleDebug`（Windows 用 `gradlew.bat`）
- **自身 CLAUDE.md**：`machine_check/CLAUDE.md` 有完整的依赖、架构、包名等细节
- **关键注意**：APK 是编译产物，修改手机端逻辑需要改 Kotlin 源码后重新构建
- **Gradle 缓存陷阱**：`assembleDebug` 显示 `UP-TO-DATE` 但 APK 未更新时，是 Gradle 增量编译缓存命中。必须先 `./gradlew clean` 再 `./gradlew :app:assembleDebug` 强制全量重编，确保 Kotlin 源码变更进入 APK

## Important Notes

- **Passwords stored in plain text** in `inspection_users.password` — no hashing. This is a security concern for production.
- **No authentication/authorization middleware** — the `[ApiController]` has no `[Authorize]` attributes. Login is purely a client-side check.
- **Database connection string** is hardcoded in `appsettings.json` with credentials. Use User Secrets or environment variables for production.
- The `bin/`, `obj/`, `publish/` directories are build artifacts and should not be committed.
- The `.vs/` directory is Visual Studio configuration, also should not be committed.

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
- Author a backlog-ready spec/issue → invoke /spec

 # #   g s t a c k 
 U s e   / b r o w s e   f r o m   g s t a c k   f o r   a l l   w e b   b r o w s i n g .   N e v e r   u s e   m c p _ _ c l a u d e - i n - c h r o m e _ _ *   t o o l s . 
 A v a i l a b l e   s k i l l s :   / o f f i c e - h o u r s ,   / p l a n - c e o - r e v i e w ,   / p l a n - e n g - r e v i e w ,   / p l a n - d e s i g n - r e v i e w , 
 / d e s i g n - c o n s u l t a t i o n ,   / r e v i e w ,   / s h i p ,   / l a n d - a n d - d e p l o y ,   / c a n a r y ,   / b e n c h m a r k ,   / b r o w s e , 
 / q a ,   / q a - o n l y ,   / d e s i g n - r e v i e w ,   / s e t u p - b r o w s e r - c o o k i e s ,   / s e t u p - d e p l o y ,   / r e t r o , 
 / i n v e s t i g a t e ,   / d o c u m e n t - r e l e a s e ,   / c o d e x ,   / c s o ,   / a u t o p l a n ,   / c a r e f u l ,   / f r e e z e ,   / g u a r d , 
 / u n f r e e z e ,   / g s t a c k - u p g r a d e . 
  
 