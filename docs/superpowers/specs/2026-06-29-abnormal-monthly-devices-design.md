# 当月异常点检设备统计面板 — 设计 spec

日期: 2026-06-29
状态: 已确认

## 概述

在前端网页的设备名称筛选区域追加一个面板，列出当前月份有异常点检的设备型号及数量，支持点击跳转。

## 布局与 UI

位置：设备下拉框（`#deviceSelect`）下方，同一 `filter-item` 内。

```
┌─ 设备名称 ──────────────────┐
│ [设备下拉框        ▲ ▼]     │
│                              │
│ ⚠️ 异常设备 (3台)    [展开] │
│ ┌──────────────────────┐     │
│ │ 设备A  ⚠️异常         │     │  ← 点击选中该设备并触发查询
│ │ 设备B  ⚠️异常         │     │
│ │ 设备C  ⚠️异常         │     │
│ └──────────────────────┘     │
└──────────────────────────────┘
```

- 面板默认折叠；有异常设备时自动展开
- 头部显示 `⚠️ 异常设备 (N台)`
- 每行可点击，点击后设置 `deviceSelect.value` → 调用 `loadData()`
- 无异常时显示 `✅ 本月无异常` 绿色状态，折叠
- 设备下拉框内异常设备追加 `⚠️异常` 后缀标记

## 数据来源

复用现有 `GET api/inspection/monthly-summary?year=&month=` API，无后端改动。

返回数据中使用的字段：
- `abnormalDevices` — 异常设备数量
- `abnormalDeviceModels` — 异常设备型号数组
- `devices[].deviceModel` + `devices[].isAbnormal` — 用于标记下拉框

## 数据流

```
loadData() / 切换年/月
  → GET monthly-summary?year=&month=
  → 填充异常面板 HTML
  → 标记 deviceSelect option 中的异常设备
  → 面板行 click → deviceSelect.value = deviceModel → loadData()
```

## 边界情况

| 情况 | 处理 |
|------|------|
| 无异常设备 | 显示 `✅ 本月无异常`，绿色面板，折叠 |
| API 失败 | 面板隐藏，不阻塞主流程，console.error |
| 切换年/月 | 随 `loadData()` 一起刷新 |
| 设备下拉框为空 | 面板隐藏 |
| 设备名过长 | `text-overflow: ellipsis`，hover title 显示全称 |
| 异常设备 > 10 个 | 列表设 `max-height` + `overflow-y: auto` |

## 实现注意事项

### 1. deviceModel 与 option.value 的精确匹配

面板行点击执行 `deviceSelect.value = deviceModel` 时，API 返回的 `abnormalDeviceModels` 值必须与 `<option value>` 严格一致。注意空格和大小写可能导致匹配失败，下拉框无法成功选中。

**处理**：使用与现有 `loadDevices()` 填充 `<option>` 时相同的值来源，确保 `monthly-summary` API 返回的 `deviceModel` 与 `deviceSelect.options[i].value` 完全一致。

### 2. 避免 loadData() 循环触发

调用链：面板点击 → `deviceSelect.value = deviceModel` → `loadData()` → `loadMonthlyAbnormalPanel()` 刷新面板。

**处理**：`loadMonthlyAbnormalPanel()` 只做面板 DOM 渲染，不修改 `deviceSelect.value`。渲染过程不触发 `change` 事件，避免死循环或选中状态闪烁。

### 3. Option 标记防重复

`markAbnormalDevices()` 每次 `loadData()` 都会执行。必须避免重复追加后缀（如 `设备A ⚠️异常 ⚠️异常`）。

**处理**：复用现有 `markUninspectedDevices()` 的 `data-original-text` 模式 — 先读取 `data-original-text` 属性恢复原始文本，再追加标记。异常标记使用 `⚠️异常` 后缀和红色文字（`style.color = '#c62828'`），与未检标记 `⚠️ 未检` 区分。同一设备可能同时有未检和异常标记（例如上月未检但在本月异常），按优先级排列：异常标记 > 未检标记。

## 实现范围

纯前端改动，仅涉及 `html/index.html`：

### HTML
- 在 `deviceSelect` 的 `filter-item` 内新增异常面板 DOM 结构

### CSS
- 异常面板样式：折叠/展开、行 hover、滚动、颜色使用现有设计系统变量
- 复用现有 `--danger`、`--danger-light`、`--warning` 等语义色

### JS
- `loadMonthlyAbnormalPanel()` — 调用 `monthly-summary`，渲染面板
- `markAbnormalDevices()` — 在下拉框 option 中标记异常设备（与现有 `markUninspectedDevices` 模式一致）
- 面板行点击事件委托 — 选中设备并触发加载
- 在 `loadData()` 流程中调用上述函数
