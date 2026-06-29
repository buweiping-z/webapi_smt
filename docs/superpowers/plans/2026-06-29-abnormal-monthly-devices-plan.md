# 当月异常点检设备统计面板 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在设备下拉框下方新增异常设备统计面板，列出当月有异常点检的设备型号及数量，支持点击跳转

**Architecture:** 纯前端改动，仅在 `html/index.html` 中新增 HTML DOM、CSS 样式、JS 函数。复用现有 `monthly-summary` API 获取异常设备数据，遵循现有 `uninspected-monthly` 面板和 `markUninspectedDevices` 标记模式

**Tech Stack:** Vanilla HTML/CSS/JS，Axios（HTTP），无新依赖

## Global Constraints

- 纯前端改动，仅涉及 `html/index.html`
- 复用 `GET api/inspection/monthly-summary?year=&month=` API，无后端改动
- 样式复用现有设计系统变量（`--danger`、`--danger-light`、`--success-light` 等）
- Option 标记复用 `data-original-text` 模式（与 `markUninspectedDevices` 一致）
- 面板渲染不修改 `deviceSelect.value`，避免循环触发

---

### Task 1: HTML — 在设备下拉框下方插入异常面板 DOM

**Files:**
- Modify: `html/index.html` — 在 `deviceSelect` 所在 `filter-item` 内、`input-row` div 之后插入异常面板

**Interfaces:**
- Produces: `#abnormalMonthlyPanel` DOM 元素（含 `#abnormalPanelHeader` 和 `#abnormalPanelContent`），供 Task 3 JS 函数操作

- [ ] **Step 1: 在 deviceSelect 的 input-row 之后插入面板 HTML**

在 `html/index.html` 第 1477-1478 行之间（`</div>` 关闭 input-row 之后，`</div>` 关闭 filter-item 之前）插入：

```html
                    <div id="abnormalMonthlyPanel" style="display:none;">
                        <div class="panel-header" id="abnormalPanelHeader">
                            ⚠️ 异常设备 (0台)
                        </div>
                        <div id="abnormalPanelContent"></div>
                    </div>
```

具体位置：`</div>` (input-row) 和 `</div>` (filter-item) 之间：

```html
                <div class="filter-item">
                    <label>设备名称</label>
                    <div class="input-row">
                        <select id="deviceSelect" style="flex:1;">
                            <option value="">请选择设备</option>
                        </select>
                        <div class="device-nav-stack">
                            <button class="btn btn-nav" onclick="prevDevice()" title="上一个设备">▲</button>
                            <button class="btn btn-nav" onclick="nextDevice()" title="下一个设备">▼</button>
                        </div>
                    </div>
                    <!-- ↓↓↓ 在此处插入异常面板 ↓↓↓ -->
                    <div id="abnormalMonthlyPanel" style="display:none;">
                        <div class="panel-header" id="abnormalPanelHeader">
                            ⚠️ 异常设备 (0台)
                        </div>
                        <div id="abnormalPanelContent"></div>
                    </div>
                    <!-- ↑↑↑ 插入结束 ↑↑↑ -->
                </div>
```

- [ ] **Step 2: 在浏览器中打开页面，确认面板 DOM 存在但默认隐藏**

```bash
# 启动前端服务器
cd html && python -m http.server 8080
```

打开 http://localhost:8080，在 DevTools Console 中执行：
```js
document.getElementById('abnormalMonthlyPanel')
```
预期：返回非 null 的 DOM 元素，`style.display` 为 `'none'`

- [ ] **Step 3: Commit**

```bash
git add html/index.html
git commit -m "feat: 添加异常设备面板 HTML 结构"
```

---

### Task 2: CSS — 异常面板样式

**Files:**
- Modify: `html/index.html` — 在 `<style>` 块中 `#uninspectedMonthlyPanel` 样式附近新增异常面板样式

**Interfaces:**
- Produces: CSS 类 `.abnormal-device-row`、`#abnormalMonthlyPanel` 样式，供 Task 3 渲染使用

- [ ] **Step 1: 在 #uninspectedMonthlyPanel 样式块之后新增异常面板 CSS**

在 `html/index.html` 第 1264 行（`}` 关闭 `@media (max-width: 768px)` 中 `.uninspected-table` 块）之后插入：

```css
        /* ============================================================
           Abnormal Monthly Panel
           ============================================================ */
        #abnormalMonthlyPanel {
            margin-top: 6px;
            padding: 8px 10px;
            background: var(--danger-light);
            border: 1px solid var(--danger-border);
            border-radius: var(--radius-sm);
            font-size: 12px;
        }

        #abnormalMonthlyPanel.all-clear {
            background: var(--success-light);
            border-color: var(--success-border);
        }

        #abnormalMonthlyPanel .panel-header {
            display: flex;
            align-items: center;
            gap: 6px;
            margin-bottom: 0;
            font-family: var(--font-heading);
            font-size: 12px;
            font-weight: 600;
            color: #991B1B;
            cursor: pointer;
            user-select: none;
        }

        #abnormalMonthlyPanel.all-clear .panel-header {
            color: #065F46;
        }

        #abnormalMonthlyPanel .panel-header::after {
            content: '';
            margin-left: auto;
            width: 0;
            height: 0;
            border-left: 4px solid transparent;
            border-right: 4px solid transparent;
            border-top: 5px solid currentColor;
            transition: transform var(--transition-fast);
        }

        #abnormalMonthlyPanel.collapsed .panel-header::after {
            transform: rotate(-90deg);
        }

        #abnormalMonthlyPanel .panel-header .abnormal-count {
            font-weight: 700;
            color: #DC2626;
        }

        #abnormalMonthlyPanel.all-clear .panel-header .abnormal-count {
            color: #065F46;
        }

        .abnormal-device-list {
            margin-top: 6px;
            max-height: 260px;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 2px;
        }

        .abnormal-device-row {
            padding: 4px 8px;
            border-radius: var(--radius-xs);
            cursor: pointer;
            color: #7F1D1D;
            transition: background var(--transition-fast);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        .abnormal-device-row:hover {
            background: rgba(239, 68, 68, 0.12);
            color: #991B1B;
        }

        .abnormal-device-row .abnormal-icon {
            margin-right: 4px;
            font-size: 11px;
        }
```

- [ ] **Step 2: 刷新页面，DevTools 检查样式是否正确加载**

在浏览器 DevTools Elements 面板中检查 `#abnormalMonthlyPanel`，确认所有 CSS 属性生效。

- [ ] **Step 3: Commit**

```bash
git add html/index.html
git commit -m "style: 异常设备面板 CSS 样式"
```

---

### Task 3: JS — loadMonthlyAbnormalPanel() 函数

**Files:**
- Modify: `html/index.html` — 在 `<script>` 块中，`loadUninspectedMonthly()` 函数之后新增函数

**Interfaces:**
- Consumes: `GET api/inspection/monthly-summary?year=&month=` → `{ abnormalDevices: number, abnormalDeviceModels: string[] }`
- Consumes: `#abnormalMonthlyPanel`、`#abnormalPanelHeader`、`#abnormalPanelContent` DOM 元素（来自 Task 1）
- Produces: `loadMonthlyAbnormalPanel()` 函数 — 无参数，无返回值。被 Task 5 在 `loadMonthlySummary()` 中调用

- [ ] **Step 1: 编写 loadMonthlyAbnormalPanel() 函数**

在 `html/index.html` 中 `loadUninspectedMonthly()` 函数结束后（约第 2121 行 `}` 之后）新增：

```javascript
        // 加载当月异常点检设备清单
        async function loadMonthlyAbnormalPanel() {
            const yearSelect = document.getElementById('yearSelect');
            const monthSelect = document.getElementById('monthSelect');
            if (!yearSelect || !monthSelect) return;

            const year = parseInt(yearSelect.value);
            const month = parseInt(monthSelect.value);
            if (!year || !month) return;

            const panel = document.getElementById('abnormalMonthlyPanel');
            const header = document.getElementById('abnormalPanelHeader');
            const content = document.getElementById('abnormalPanelContent');
            if (!panel || !header || !content) return;

            try {
                const response = await axios.get(`${API_BASE_URL}/api/Inspection/monthly-summary`, {
                    params: { year, month }
                });
                const data = response.data;
                const count = data.abnormalDevices || 0;
                const models = data.abnormalDeviceModels || [];

                panel.style.display = 'block';

                if (count === 0) {
                    panel.classList.add('all-clear');
                    panel.classList.add('collapsed');
                    header.innerHTML = '✅ 本月无异常';
                } else {
                    panel.classList.remove('all-clear');
                    panel.classList.remove('collapsed');
                    header.innerHTML = `⚠️ 异常设备 <span class="abnormal-count">(${count}台)</span>`;

                    let html = '<div class="abnormal-device-list">';
                    models.forEach(model => {
                        html += `<div class="abnormal-device-row" data-device-model="${model}" title="${model}">`;
                        html += `<span class="abnormal-icon">⚠️</span>${model}`;
                        html += `</div>`;
                    });
                    html += '</div>';
                    content.innerHTML = html;
                }

                // 标记下拉框中的异常设备
                markAbnormalDevices(models);

            } catch (error) {
                console.error('加载异常设备清单失败:', error);
                if (panel) panel.style.display = 'none';
            }
        }
```

- [ ] **Step 2: 验证函数语法无误**

在浏览器 Console 中执行 `typeof loadMonthlyAbnormalPanel`，预期返回 `"function"`。

- [ ] **Step 3: Commit**

```bash
git add html/index.html
git commit -m "feat: loadMonthlyAbnormalPanel 函数 — 调用 monthly-summary API 渲染异常面板"
```

---

### Task 4: JS — markAbnormalDevices() 函数

**Files:**
- Modify: `html/index.html` — 在 `markUninspectedDevices()` 函数之后新增

**Interfaces:**
- Consumes: `abnormalModels: string[]` — 异常设备型号数组
- Produces: `markAbnormalDevices(abnormalModels)` 函数 — 在 `#deviceSelect` 的 option 中标记异常设备。被 Task 3 的 `loadMonthlyAbnormalPanel()` 调用
- Produces: 修改 `markUninspectedDevices()` 使其在异常标记后运行时不会覆盖异常标记

- [ ] **Step 1: 编写 markAbnormalDevices() 函数，并修改 markUninspectedDevices() 防覆盖**

在 `html/index.html` 中 `markUninspectedDevices()` 函数之后（约第 2144 行 `}` 之后）新增 `markAbnormalDevices()`：

```javascript
        function markAbnormalDevices(abnormalModels) {
            const select = document.getElementById('deviceSelect');
            if (!select || select.options.length <= 1) return;

            const abnormalSet = new Set(abnormalModels);

            for (let i = 1; i < select.options.length; i++) {
                const option = select.options[i];
                const deviceModel = option.value;
                // 先恢复原始文本（与 markUninspectedDevices 共享 data-original-text）
                const originalText = option.getAttribute('data-original-text') || option.textContent;
                if (!option.getAttribute('data-original-text')) {
                    option.setAttribute('data-original-text', originalText);
                }

                if (abnormalSet.has(deviceModel)) {
                    option.textContent = option.getAttribute('data-original-text') + ' ⚠️异常';
                    option.style.color = '#c62828';
                }
                // 注意：不在 abnormalSet 中的设备不在此处恢复，交给 markUninspectedDevices 处理
            }
        }
```

同时修改 `markUninspectedDevices()` 函数（约第 2123 行），确保不覆盖已有的异常标记。找到该函数中 `else` 分支：

```javascript
                } else {
                    option.textContent = option.getAttribute('data-original-text');
                    option.style.color = '';
                }
```

替换为（避免清除 markAbnormalDevices 已设置的标记）：

```javascript
                } else {
                    // 不覆盖异常标记：如果当前 textContent 已包含 ⚠️异常 则保留
                    if (!option.textContent.includes('⚠️异常')) {
                        option.textContent = option.getAttribute('data-original-text');
                        option.style.color = '';
                    }
                }
```

- [ ] **Step 2: 验证函数存在且不对空下拉框报错**

在浏览器 Console 中执行：
```js
markAbnormalDevices([])
markAbnormalDevices(['不存在的设备'])
```
预期：无报错，下拉框无变化。

- [ ] **Step 3: Commit**

```bash
git add html/index.html
git commit -m "feat: markAbnormalDevices 函数 + markUninspectedDevices 防覆盖"
```

---

### Task 5: JS — 点击事件委托 + 集成到 loadMonthlySummary()

**Files:**
- Modify: `html/index.html` — 新增点击事件委托，在 `loadMonthlySummary()` 中插入 `loadMonthlyAbnormalPanel()` 调用

**Interfaces:**
- Consumes: `loadMonthlyAbnormalPanel()`（来自 Task 3）
- Consumes: `markAbnormalDevices()`（来自 Task 4）
- Produces: 面板行点击 → `deviceSelect.value` → `loadData()` 的完整交互闭环

- [ ] **Step 1: 在 loadMonthlySummary() 中插入 loadMonthlyAbnormalPanel() 调用**

找到 `html/index.html` 中 `loadMonthlySummary()` 函数内的 `loadUninspectedMonthly();` 调用（约第 2048 行），在其下方新增：

```javascript
                loadUninspectedMonthly();
                loadMonthlyAbnormalPanel();
```

完整上下文：
```javascript
                fillFrequencyRow('daily',   data.daily);
                fillFrequencyRow('weekly',  data.weekly);
                fillFrequencyRow('monthly', data.monthly);

                loadUninspectedMonthly();
                loadMonthlyAbnormalPanel();
```

- [ ] **Step 2: 添加面板点击事件委托**

在 `html/index.html` 的 `<script>` 末尾、初始化代码之前（约在 DOMContentLoaded 监听器内或全局作用域），新增事件委托。找到合适的全局事件绑定位置（如搜索 `document.addEventListener('click'` 或 `document.addEventListener('DOMContentLoaded'`），在其中新增点击处理。

查找现有的事件委托代码位置：
```javascript
// 搜索 html/index.html 中已有的 document.addEventListener('click' 或事件委托模式
```

在合适的全局 script 位置新增：

```javascript
        // 异常设备面板 — 点击行跳转到对应设备
        document.addEventListener('click', function(e) {
            const row = e.target.closest('.abnormal-device-row');
            if (!row) return;

            const deviceModel = row.getAttribute('data-device-model');
            if (!deviceModel) return;

            const deviceSelect = document.getElementById('deviceSelect');
            // 确保 deviceModel 与 option value 匹配
            for (let i = 0; i < deviceSelect.options.length; i++) {
                if (deviceSelect.options[i].value === deviceModel) {
                    deviceSelect.value = deviceModel;
                    loadData();
                    return;
                }
            }
            console.warn('异常设备面板：下拉框中未找到设备型号 ' + deviceModel);
        });
```

- [ ] **Step 3: 添加面板头部点击折叠/展开**

在上一步的事件委托中追加面板头部折叠逻辑。将 Step 2 的代码扩展为：

```javascript
        // 异常设备面板 — 点击行跳转 + 点击头部折叠/展开
        document.addEventListener('click', function(e) {
            // 点击异常面板头部 → 折叠/展开
            const headerEl = e.target.closest('#abnormalPanelHeader');
            if (headerEl) {
                const panel = document.getElementById('abnormalMonthlyPanel');
                if (panel && !panel.classList.contains('all-clear')) {
                    panel.classList.toggle('collapsed');
                }
                return;
            }

            // 点击异常设备行 → 跳转到该设备
            const row = e.target.closest('.abnormal-device-row');
            if (!row) return;

            const deviceModel = row.getAttribute('data-device-model');
            if (!deviceModel) return;

            const deviceSelect = document.getElementById('deviceSelect');
            for (let i = 0; i < deviceSelect.options.length; i++) {
                if (deviceSelect.options[i].value === deviceModel) {
                    deviceSelect.value = deviceModel;
                    loadData();
                    return;
                }
            }
            console.warn('异常设备面板：下拉框中未找到设备型号 ' + deviceModel);
        });
```

并在 CSS 中补充折叠状态隐藏列表的样式。找到 Task 2 添加的 `#abnormalMonthlyPanel` CSS 区域，追加：

```css
        #abnormalMonthlyPanel.collapsed .abnormal-device-list {
            display: none;
        }

        #abnormalMonthlyPanel.collapsed .panel-header {
            margin-bottom: 0;
        }
```

- [ ] **Step 4: 端到端测试**

启动前端服务器并测试完整流程：

```bash
cd html && python -m http.server 8080
```

1. 打开 http://localhost:8080，登录
2. 选择有异常数据的年份/月份
3. 确认异常面板出现在设备下拉框下方
4. 确认面板头部显示异常设备数量
5. 点击面板头部，验证折叠/展开
6. 点击异常设备行，验证自动跳转到该设备的月点检表格
7. 验证设备下拉框中异常设备有 `⚠️异常` 标记
8. 切换到无异常数据的月份，验证面板显示 `✅ 本月无异常`
9. 无异常时面板应自动折叠

- [ ] **Step 5: Commit**

```bash
git add html/index.html
git commit -m "feat: 异常面板点击交互 + 集成到 loadMonthlySummary 流程"
```
