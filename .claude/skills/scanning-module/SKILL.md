---
name: scanning-module
description: Use when working on the Android 扫码/条码扫描 module — modifying QrCodeScanner, BarcodeAnalyzer, ScannerOverlay, or their dependencies; debugging scan-then-crash, scan-then-exit; adding new barcode types; or touching CameraX + ML Kit + ZXing integration in machine_check/
---

# 扫码模块 (Scanning Module)

## Overview

双通道条码扫描架构：CameraX 采集图像 → `BarcodeAnalyzer` 两路并行识别 → Compose UI 展示。

**核心原则：`QrCodeScanner` 只负责 UI 和 CameraX 绑定，识别逻辑全部在 `BarcodeAnalyzer` 中。不要在 Composable 内部写 ML Kit/ZXing 代码。**

## When to Use

- 扫码后 App 崩溃、扫码后退出、扫码无反应
- 修改 QR/DM 码识别逻辑
- 添加新条码类型支持
- 修改取景框 UI
- CameraX 绑定报错

## 文件地图

```
machine_check/app/src/main/java/com/machine_check/inspection/ui/components/
├── QrCodeScanner.kt       ← Composable 入口，CameraX 绑定
├── BarcodeAnalyzer.kt     ← 双通道分析器（ML Kit + ZXing）
├── ScannerOverlay.kt      ← 取景框叠加层（四角 + 扫描线动画）
├── BinarizationPipeline.kt ← Otsu / Sauvola 二值化（PCB 刻印码用）
└── ZxingHelpers.kt        ← GrayscaleLuminanceSource + IdentityBinarizer

machine_check/app/src/main/java/com/machine_check/inspection/ui/scan/
├── ScanScreen.kt          ← 扫码页面（工号/设备型号输入 + 扫码触发）
└── ScanViewModel.kt       ← 扫码状态管理

machine_check/app/src/main/java/com/machine_check/inspection/utils/
└── ImageUtils.kt          ← YUV→灰度像素提取

machine_check/app/build.gradle.kts
└── dependencies            ← ZXing / CameraX / ML Kit
```

## 双通道架构

```
                    CameraX ImageProxy
                           │
                    BarcodeAnalyzer.analyze()
                       ╱         ╲
             通道 0: ML Kit    通道 1: ZXing
             (纸质 QR/DM)      (PCB 刻印 DM)
                  │                  │
                  │           Y平面灰度提取
                  │           取景框裁剪
                  │           Otsu ─┐
                  │           Sauvola ┤ 并行竞速
                  │           ZXing DM Reader
                  │                  │
                  └──── 竞速 ────────┘
                         │
                  reportResult()
                  (500ms 去重冷却)
                         │
            android.os.Handler(mainLooper).post
                         │
                  onBarcodeScanned(rawValue)
```

- **通道 0 (ML Kit)**: 直接送原图给 ML Kit，识别纸质 QR 码和 Data Matrix 码
- **通道 1 (ZXing)**: Y 平面提取灰度 → 裁剪取景框 → Otsu/Sauvola 并行二值化 → ZXing DataMatrixReader 解码
- **竞速**: 两通道并行，`select` 取先到者，取消另一通道
- **取景框过滤**: 两通道都只识别取景框内的条码（`frameSizeFraction = 0.45`）
- **超时**: 总超时 2000ms，ZXing 通道 1500ms

## 关键依赖

```kotlin
// build.gradle.kts — 缺一不可：
implementation("androidx.camera:camera-camera2:1.4.2")
implementation("androidx.camera:camera-lifecycle:1.4.2")
implementation("androidx.camera:camera-view:1.4.2")
implementation("com.google.mlkit:barcode-scanning:17.3.0")
implementation("com.google.zxing:core:3.5.3")          // ← 容易遗漏！
```

## QrCodeScanner 正确写法

```kotlin
@Composable
fun QrCodeScanner(
    onBarcodeScanned: (String) -> Unit,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    // 1. 权限检查（CAMERA）
    // 2. val analyzer = remember { BarcodeAnalyzer(onBarcodeScanned) }
    // 3. AndroidView(factory = { PreviewView... }) — 只做 CameraX 绑定
    // 4. imageAnalysis.setAnalyzer(mainExecutor, analyzer)
    // 5. DisposableEffect(analyzer) { onDispose { analyzer.close() } }
    // 6. ScannerOverlay(isActive = isActive)
}
```

**红线**：
- analyzer 必须用 `remember {}` 创建，不能放在 factory 里
- `DisposableEffect` 的 key 必须是 `analyzer`，不能是 `Unit`
- 不要在 `factory` 里调用 `BarcodeScanning.getClient()` 或 `Executors.newSingleThreadExecutor()`
- 不要自己在 Composable 里维护 `lastScannedCode` 去重 — `BarcodeAnalyzer` 内部已做

## BarcodeAnalyzer 生命周期

```kotlin
class BarcodeAnalyzer(onBarcodeScanned: (String) -> Unit) : ImageAnalysis.Analyzer {
    val isActive = AtomicBoolean(true)   // ← 控制分析启停

    fun close() {
        isActive.set(false)              // 1. 先停分析
        analysisScope.cancel()           // 2. 取消协程
        binarizeExecutor.shutdown()      // 3. 关线程池
        mlKitScanner.close()             // 4. 释放 ML Kit
    }
}
```

`isActive` 检查在 `analyze()` 入口和 `reportResult()` 两处，确保 `close()` 后不会再回调。

## 常见问题

### 1. 扫描完就退出/崩溃

**根因**: QrCodeScanner 内联 ML Kit 代码，`DisposableEffect(Unit)` 清理时机错误。

**修复**: 恢复 `BarcodeAnalyzer` 架构，见上方正确写法。

### 2. ZXing 类找不到

**症状**: `Unresolved reference 'zxing'`

**修复**: `build.gradle.kts` 添加 `implementation("com.google.zxing:core:3.5.3")`

### 3. PCB DM 码识别率低

**检查顺序**:
1. 确认 `BinarizationPipeline` 的 Otsu/Sauvola 阈值参数
2. 确认取景框 `frameSizeFraction = 0.45` 合理
3. 确认 `setTargetResolution(Size(1920, 1080))` 分辨率够高

### 4. 取景框不显示

`ScannerOverlay` 在 `QrCodeScanner` 内部渲染。确保 `QrCodeScanner` 没有被外层 Box 遮挡。

## 验证命令

```bash
# 编译检查
cd machine_check && ./gradlew :app:compileDebugKotlin

# 构建 APK
cd machine_check && ./gradlew :app:assembleDebug

# APK 输出位置
# app/build/outputs/apk/debug/app-debug.apk
```
