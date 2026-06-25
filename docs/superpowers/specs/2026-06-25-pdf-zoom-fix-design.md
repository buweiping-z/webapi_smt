# PDF 导出浏览器缩放兼容修复

**日期**: 2026-06-25
**类型**: Bug 修复

## 问题

浏览器缩放（Ctrl+- / Ctrl+滚轮）后导出 PDF，表格内容溢出页面边界。

## 根因

`exportPDF()` 中通过 `table.scrollWidth` 获取表格宽度来计算 `capW`（PDF 容器宽度）。浏览器缩放会改变 CSS 视口大小，导致 `scrollWidth` 取值失真——缩放后的值不能反映表格在 PDF 渲染上下文中的真实宽度需求。同时 html2canvas 的渲染上下文也受页面缩放影响，最终图片尺寸与 A3 页面不匹配，内容溢出。

## 方案

将宽度计算从 DOM 测量改为**列结构估算**，彻底解耦页面缩放状态。

## 改动

文件：`html/index.html`，`exportPDF()` 函数

### 现状（第 2697-2700 行）

```javascript
table.getBoundingClientRect();
const tableW = table.scrollWidth;
const capW = Math.max(tableW + 40, 1200);
```

### 改为

```javascript
const daysInMonth = new Date(currentYear, currentMonth, 0).getDate();
const fixedColsW = 60 + 200 + 60 + 50;  // 编号+项目+内容+周期 min-width
const estimatedTableW = fixedColsW + daysInMonth * 55;
const capW = Math.min(Math.max(estimatedTableW + 40, 1200), 3500);
```

## 参数说明

| 参数 | 值 | 来源 |
|------|-----|------|
| 编号列 | 60px | `min-width:60px` |
| 项目列 | 200px | `min-width:200px` |
| 内容列 | 60px | `min-width:60px` |
| 周期列 | 50px | `min-width:50px` |
| 每天列 | 55px | `min-width:50px` + 5px 缓冲 |
| 下限 | 1200px | 保持原有逻辑 |
| 上限 | 3500px | 防止极端情况超出 A3 横向 |

## 边界验证

- 2 月（28 天）：370 + 1540 + 40 = 1950 → capW = 1950
- 31 天大月：370 + 1705 + 40 = 2115 → capW = 2115
- 下限触发：天数 ≤ 14 时 capW 保持 1200
- 上限触发：天数 ≥ 57 时 capW 保持 3500（实际不存在）
