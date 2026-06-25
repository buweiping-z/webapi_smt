# PDF 导出浏览器缩放兼容修复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复浏览器缩放后 PDF 导出表格溢出页面边界的问题

**Architecture:** 将 `exportPDF()` 中 `capW` 的计算方式从 DOM 测量（`table.scrollWidth`）改为列结构估算，彻底解耦浏览器缩放状态

**Tech Stack:** 纯 JavaScript / HTML，无外部依赖变更

## Global Constraints

- 仅修改 `html/index.html`，不涉及后端或 Android 端
- A3 横向 PDF，`html2canvas scale: 1.5` 保持不变
- 表格列结构：编号(60) + 项目(200) + 内容(60) + 周期(50) + N 天(55)

---

### Task 1: 替换 capW 计算逻辑

**Files:**
- Modify: `html/index.html:2697-2700`

**Interfaces:**
- Consumes: `currentYear`, `currentMonth`（已在函数作用域内）
- Produces: `capW`（后续 html2pdf 配置使用，类型不变：`number`）

- [ ] **Step 1: 定位现有代码**

打开 `html/index.html`，找到 `exportPDF()` 函数内的第 2697-2700 行：

```javascript
            // 表格真实宽度（强制 reflow 确保 scrollWidth 准确，避免布局未完成时取值偏小）
            table.getBoundingClientRect();
            const tableW = table.scrollWidth;
            const capW = Math.max(tableW + 40, 1200);
```

- [ ] **Step 2: 替换为新代码**

将上述 4 行替换为：

```javascript
            // 从列结构估算表格宽度，避免浏览器缩放导致 scrollWidth 失真
            const daysInMonth = new Date(currentYear, currentMonth, 0).getDate();
            const fixedColsW = 60 + 200 + 60 + 50;  // 编号+项目+内容+周期 min-width
            const estimatedTableW = fixedColsW + daysInMonth * 55;
            const capW = Math.min(Math.max(estimatedTableW + 40, 1200), 3500);
```

- [ ] **Step 3: 验证代码正确性**

检查替换后的上下文是否连续完整（第 2700 行附近应与后续印章逻辑无缝衔接）：

```javascript
            const capW = Math.min(Math.max(estimatedTableW + 40, 1200), 3500);

            // CSS 电子印章（纯样式，无图片，无 canvas 污染）
            function sealHTML(name, signed) {
```

- [ ] **Step 4: 浏览器验证**

1. 启动后端：`dotnet run`
2. 启动前端：`cd html && python -m http.server 8080`
3. 浏览器打开 `http://localhost:8080`
4. 登录 → 选择设备 → 选择月份 → 查询
5. **100% 缩放**：点击「导出PDF」，确认正常
6. **Ctrl+- 缩放到 67%**：再次「导出PDF」，确认表格不溢出
7. **Ctrl+- 缩放到 50%**：再次「导出PDF」，确认表格不溢出
8. **Ctrl+0 恢复 100%**：再次「导出PDF」，确认仍然正常

- [ ] **Step 5: 提交**

```bash
git add html/index.html
git commit -m "fix: PDF 导出宽度计算改为列结构估算，修复浏览器缩放导致的表格溢出"
```
