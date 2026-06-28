package com.machine_check.inspection.ui.components

import kotlin.math.sqrt

/**
 * 二值化管道
 * 专为 PCB 激光刻印（亮码暗底、低对比度、光照不均）设计
 * - Otsu 全局阈值：适合光照均匀场景，速度快
 * - Sauvola 自适应阈值：适合 PCB 表面反光或阴影场景
 * - 统一输出 ZXing 期望的 BitMatrix（暗背景 0 + 亮码点 1）
 */
object BinarizationPipeline {

    /**
     * Otsu 全局阈值二值化
     * 计算最大类间方差确定最佳阈值
     */
    fun otsuBinarize(pixels: IntArray, width: Int, height: Int): com.google.zxing.common.BitMatrix {
        val histogram = IntArray(256)
        val total = pixels.size
        for (p in pixels) histogram[p.coerceIn(0, 255)]++

        var sum = 0.0
        for (i in 0 until 256) sum += i * histogram[i]

        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var threshold = 0

        for (t in 0 until 256) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF

            val variance = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (variance > maxVar) {
                maxVar = variance
                threshold = t
            }
        }

        val matrix = com.google.zxing.common.BitMatrix(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y * width + x] > threshold) {
                    matrix.set(x, y)
                }
            }
        }
        return matrix
    }

    /**
     * Sauvola 自适应阈值二值化
     * 逐像素局部窗口计算，适应光照不均场景
     * 处理传入的全部像素区域（调用方可自行裁剪区域后再传入）
     *
     * @param pixels 灰度像素数组 [0, 255]
     * @param width 图像宽度
     * @param height 图像高度
     * @param windowSize 局部窗口大小（必须为奇数）
     * @param k 灵敏度系数（默认 0.5，增大阈值对噪声更敏感）
     * @param R 标准差上限（默认 128.0，控制对比度自适应范围）
     */
    fun sauvolaBinarize(
        pixels: IntArray, width: Int, height: Int,
        windowSize: Int = 15, k: Double = 0.5, R: Double = 128.0
    ): com.google.zxing.common.BitMatrix {
        val halfWin = windowSize / 2
        val matrix = com.google.zxing.common.BitMatrix(width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0; var sumSq = 0.0; var count = 0
                val wl = (x - halfWin).coerceAtLeast(0)
                val wt = (y - halfWin).coerceAtLeast(0)
                val wr = (x + halfWin).coerceAtMost(width - 1)
                val wb = (y + halfWin).coerceAtMost(height - 1)

                for (wy in wt..wb) {
                    val rowOff = wy * width
                    for (wx in wl..wr) {
                        val v = pixels[rowOff + wx].toDouble()
                        sum += v; sumSq += v * v; count++
                    }
                }
                val mean = sum / count
                val variance = (sumSq / count) - mean * mean
                val stdDev = sqrt(variance.coerceAtLeast(0.0))
                // 完全均匀区域 (stdDev ≈ 0) 使用均值作为阈值，否则使用 Sauvola 公式
                val threshold = if (stdDev < 1e-10) mean
                                else mean * (1.0 + k * (stdDev / R - 1.0))

                if (pixels[y * width + x] > threshold) matrix.set(x, y)
            }
        }
        return matrix
    }
}