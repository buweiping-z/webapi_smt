# Bug 记录 — 图片点检功能

> 本文档记录图片点检功能开发与测试过程中发现的所有 bug。
> 进入项目后先阅读本文档，避免重复踩坑。

---

## Bug 1: SKBitmap.Decode 损坏图片导致服务崩溃

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `016cd8c` |
| **严重度** | P0 — 服务崩溃 |

**现象:** 上传魔法字节有效但内容损坏的图片（如 JPEG header + 垃圾数据）时，服务进程崩溃，HTTP 000。

**根因:** `PhotosController.cs` 中 `SKBitmap.Decode(ms)` 对损坏数据抛出未处理异常。SKCodec 有 try/catch 但 SKBitmap 没有。

**修复:** `SKBitmap.Decode` 包裹 try/catch，异常时返回 400 "无法解码图片"。

**教训:** 所有第三方库调用（尤其是图像/文件处理）都需要 try/catch，不能假设输入总是合法的。

---

## Bug 2: status 列 Data Truncated

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `73b396a` |
| **严重度** | P0 — 写入失败 |

**现象:** Android 提交点检时报 `Data truncated for column 'status' at row 1`。

**根因:** MySQL `inspection_records.status` 列为 `VARCHAR(10)`，而新状态值 `"pending_photo"` = 13 字符，写入被截断。

**修复:**
- 状态值缩短: `"pending_photo"` → `"pending"`
- 启动时 ALTER TABLE MODIFY status VARCHAR(20)
- AppDbContext Fluent API 加 HasMaxLength(20)

**教训:** 新增枚举/状态值时，先确认数据库列长度是否足够。不要假设 VARCHAR 默认够用。

---

## Bug 3: 两阶段提交流程 — 提交时不应要求已有照片

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `b909d16` |
| **严重度** | P1 — 用户体验阻塞 |

**现象:** Android 提交时校验 `requirePhoto && abnormal && photoLocalPath == null` → 阻止提交，但设计文档要求「先保存后拍照」的两阶段提交。

**根因:** 提交前验证把拍照作为前置条件（Phase 1），违背了 Phase 1 保存文字、Phase 2 上传照片的设计。

**修复:** 移除提交前的拍照强制校验，改为：
1. Phase 1: 提交文字结果 → `records/save` → 获取 `pendingPhotoItems`
2. Phase 2: 顶部 banner 显示缺照片项，逐项拍照→即时上传

**教训:** 多人协作时前后端的设计文档必须严格对齐。验证逻辑应放在正确的阶段。

---

## Bug 4: Android 照片上传路径错误 — content URI ≠ 文件路径

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `4a5191a` |
| **严重度** | P0 — 照片上传静默失败 |

**现象:** 拍照后上传显示 0/1，照片从未到达服务端。

**根因:** `FileProvider.getUriForFile()` 返回 content:// URI，`uri.path` 返回 `/cache/inspection_photos/photo_xxx.jpg`（content URI 路径），不是真实文件系统路径。`uploadPhoto()` 中 `File(filePath).exists()` 返回 false。

**修复:** 拍照前用 `photoFile.absolutePath` 保存真实路径（如 `/data/data/.../cache/inspection_photos/photo_xxx.jpg`），上传时使用此路径。

**教训:** Android `Uri.path` ≠ 文件系统路径。FileProvider 的 content URI 不能当文件路径用。拍照前就要保存好 `File.absolutePath`。

---

## Bug 5: 数值点检 isNormal 始终为 false

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `35c52d2` |
| **严重度** | P1 — 数据显示错误 |

**现象:** 前端表格中所有数值点检（温度 25.5、压力 0.8 等）都显示为红色异常。

**根因:** `SaveRecordItem` DTO 没有 `IsNormal` 字段。服务端 `records/save` 用 `saveValue == "正常"` 推导 `isNormal`，数值 `"25.5" != "正常"` → 永远 false。

**修复:**
- C# `SaveRecordItem` 加 `IsNormal` 属性
- SQL: `saveValue == "正常"` → `item.IsNormal`
- Android `SaveRecordItem` 加 `isNormal` 字段
- ViewModel 传 `isNormal = inRange`

**教训:** DTO 设计时不要依赖字符串匹配推导布尔值。显式字段比隐式推导可靠 100 倍。

---

## Bug 6: 周检/月检 frequency 硬编码为"日"

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `1001917` |
| **严重度** | P1 — 频率状态错误 |

**现象:** 周检和月检完成后，`frequencies-available` 仍显示 available=true（未置灰）。

**根因:**
1. `records/save` 硬编码 `var frequency = "日"`，`SaveDailyRecordRequest` 没有 `Frequency` 字段
2. `periodKey` 全部使用 `yyyy-MM-dd` 格式，未调用 `GeneratePeriodKey(frequency, date)`
3. 周检被存为 `frequency="日", periodKey="2026-06-19"`，`IsPeriodAllNormal(device, "周", "2026-W25")` 找不到记录

**修复:**
- C#/Android `SaveDailyRecordRequest` 加 `Frequency` 字段
- `records/save` 用 `request.Frequency` 替代硬编码
- 所有 `periodKey` 生成改用 `GeneratePeriodKey(frequency, date)`

**教训:** 任何涉及「频率」的功能都要确认三处一致：模板 frequency、record frequency、periodKey 格式。日/周/月三者的 periodKey 格式不同。

---

## Bug 7: 正常点检格也显示照片标记 📷

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `0d100c0` |
| **严重度** | P2 — 视觉干扰 |

**现象:** 正常（○）的格子也显示 📷 照片标记。

**根因:** 前端渲染时只要有照片就显示标记，未区分正常/异常。

**修复:** 加条件 `!record.isNormal`，仅异常格显示照片标记。

**教训:** 照片是故障追溯用的——正常时不需要看照片，不要增加视觉噪音。

---

## Bug 8: 填表时已拍照 → 提交后重复要求拍照

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `df918d5` |
| **严重度** | P1 — 用户体验差 |

**现象:** 填表时选了"异常"并拍照，提交后 banner 仍显示"📷 拍照"按钮，要再拍一次。

**根因:** 提交后收到 `pendingPhotoItems`，但未检查哪些项已有本地照片（`photoLocalPath`），全部要求重新拍照。

**修复:** 收到 `pendingPhotoItems` 后遍历 missingItems，已设置 `photoLocalPath` 的自动压缩上传（`autoUploadExistingPhoto`），不弹拍照界面。

**教训:** UI 状态与数据状态需要双向同步。本地已有数据时优先使用，不要强迫用户重复操作。

---

## Bug 9: 重检时旧照片覆盖新上传

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `d42a22d` |
| **严重度** | P0 — 数据丢失（新照片未上传） |

**现象:** 第一次异常→拍照→上传正常。第二次重检同一项异常→拍新照片→提交，但新照片未上传到服务端。

**根因:** `records/save` 计算 `pendingPhotoItems` 时查询 DB 已有照片。重检时旧照片还在 DB 中 → `missingItems` 为空 → 标记为 `submitted` → Android 端不触发新上传。

**修复:** 重检时先删除异常+requirePhoto 项的旧照片（DB 记录 + 磁盘文件），再标记为 pending，强制重新上传。

**教训:** 任何涉及「覆盖」「重检」「重新提交」的逻辑，都要先清理旧数据再检查。缓存/已有数据是最常见的漏网之鱼。

---

---

## Bug 10: 多照片 Phase 2 — 拍照一张后自动提交，无法追加

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `aec70e7` |
| **严重度** | P1 — 功能不符合设计 |

**现象:** 多照片升级后，Phase 2 提醒拍照只能拍 1 张，上传后自动完成提交，用户无法追加第 2-5 张。

**根因:**
1. `onPhotoTaken()` 中 `if (newUploaded >= total)` → 自动设 `phase2Pending = false`，直接完成
2. Banner 中 `photoUploadedCount > 0` 就显示 ✓ 隐藏拍照按钮，无法再拍

**修复:**
- 移除 `onPhotoTaken()` 的自动完成逻辑
- Banner 在上传后仍然显示拍照按钮（未满 5 张时）
- 底部按钮改为「完成提交」，所有异常项 ≥1 张后可点击
- 新增 `finishPhase2()` 方法

**教训:** 从单张升级到多张时，所有「满 1 就结束」的自动判断都要改为「手动完成」。自动完成的假设在新约束下不成立。

---

## Bug 11: 提交前无拍照提醒 — 异常项未拍照可直接提交

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `20df455` |
| **严重度** | P1 — 用户体验差 |

**现象:** 填表时选异常但忘记拍照，点击「提交点检」直接进入 Phase 2 banner，用户事后才知道要补拍。

**根因:** `submitInspection()` 不检查本地是否有照片，直接提交文字结果，依赖 Phase 2 补拍。但 Phase 2 弹 banner 容易被忽略。

**修复:** 提交前检查异常 + `requirePhoto` + `photoLocalPaths.isEmpty()` 的项 → 弹 AlertDialog：「📷 以下异常项需要拍照：温度、压力」→ 用户选择「去拍照」或「仍要提交」。

**教训:** 前置提醒比事后补救体验更好。关键操作前做一次完整性检查，给用户选择权。

---

## Bug 12: PDF 导出底部说明文字和编号偶尔缺失

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `b9f8ad9` |
| **严重度** | P2 — 显示缺陷 |

**现象:** PDF 导出时，底部「备注：正常标记为"○"…」和「SM-T1-03」有时不出现。

**根因:** `tableContainer.innerHTML` 外层有 `<div style="overflow-x: auto;">` wrapper，在 html2canvas 渲染时表格内容溢出容器边界，把底部说明行挤出捕获区域。

**修复:**
- 拼接 PDF HTML 时用正则去掉 `overflow-x:auto` 外层 div
- PDF 容器加 `overflow:hidden` + 底部 padding 20px
- 说明行加 `white-space:nowrap` + `flex-wrap:nowrap`
- SM-T1-03 加 `flex-shrink:0`

**教训:** `innerHTML` 的内容是为浏览器交互设计的（滚动、自适应），直接复用到 html2canvas 时交互样式可能导致渲染错位。PDF 需要的 HTML 应该是「打印友好」的静态结构。

---

## Bug 13: Android API 端口硬编码 5039，应为 8800

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `e687e8e` |
| **严重度** | P1 — 无法连接服务端 |

**现象:** Android 真机无法连接后端，所有请求超时。

**根因:** `RetrofitClient.kt` 中 `BASE_URL` 硬编码为 `http://192.168.5.6:5039/`，但实际后端运行在 8800 端口。

**修复:** 改为 `http://192.168.5.6:8800/`。

**教训:** 环境相关的配置（端口、IP）应提取为 build config 或 settings 文件，避免硬编码在源代码中。每次升级都应检查 `RetrofitClient.kt` 的端口是否匹配当前环境。

---

## Bug 14: 工作区创建时基线代码缺失 — 未提交的代码不会进入 worktree

| 属性 | 值 |
|------|-----|
| **日期** | 2026-06-19 |
| **Commit** | `7b87989` |
| **严重度** | P1 — 开发流程阻塞 |

**现象:** `EnterWorktree` 创建的分支基于 HEAD 提交，但原始仓库工作目录中有未提交的照片功能代码（Models.kt 186行 vs committed 72行、ViewModel 397行 vs 214行），导致工作区缺少基线代码，编译失败。

**根因:** 照片功能的上一次开发（Bug 1-9 修复）产生了大量代码变更，但部分未 commit。工作区只拿到 committed 的 72 行 Models.kt，缺少 `SaveRecordItem`、`SaveDailyRecordRequest`、`PendingPhotoItem` 等关键模型。

**修复:** 从原始仓库手动复制 5 个源文件 + gradle wrapper + build 配置到工作区，补全基线。

**教训:** 
1. **开发前必须确保所有基线代码已提交**。`git status` 中有未提交的 `.kt` 源文件 → 先 commit 再创建 worktree
2. 工作区只继承 git 历史，不继承工作目录的脏状态
3. Gradle wrapper jar、local.properties 等构建文件也需检查是否被 gitignore，缺失会导致编译失败

---

## 总结

| Bug | 严重度 | 类别 | 关键词 |
|-----|--------|------|--------|
| 1 | P0 | 异常处理 | SkiaSharp, try/catch, 崩溃 |
| 2 | P0 | 数据库 | VARCHAR 长度, Data truncated |
| 3 | P1 | 架构 | 两阶段提交, 校验时机 |
| 4 | P0 | Android | URI vs 文件路径, FileProvider |
| 5 | P1 | DTO | 布尔推导, 字符串匹配 |
| 6 | P1 | 频率 | 硬编码, periodKey 格式 |
| 7 | P2 | 前端 | 条件渲染, 视觉噪音 |
| 8 | P1 | UX | 本地状态, 重复操作 |
| 9 | P0 | 数据完整性 | 重检覆盖, 旧数据清理 |
| 10 | P1 | UX | 多照片自动完成, Phase 2, 追加拍照 |
| 11 | P1 | UX | 提交前提醒, 拍照弹窗, AlertDialog |
| 12 | P2 | 前端 | PDF 导出, html2canvas, overflow 裁切 |
| 13 | P1 | 配置 | 端口硬编码, RetrofitClient |
| 14 | P1 | 流程 | 工作区, 未提交代码, worktree 基线 |

**高频根因模式:**
1. **硬编码** — 频率硬编码、periodKey 硬编码、DTO 缺字段、端口硬编码
2. **路径混淆** — Android URI ≠ 文件路径
3. **旧数据未清理** — 重检/重新提交时残留数据导致逻辑跳过
4. **字符串推导** — 用字符串匹配代替显式字段传递布尔值
5. **假设输入合法** — 没给第三方库调用加 try/catch
6. **单张→多张升级陷阱** — 所有「满 1 就完成」的自动判断都要审查，改为手动完成或基于新上限
7. **innerHTML 复用陷阱** — 交互样式（overflow/scroll）在 PDF 打印场景下会导致内容缺失
