package com.machine_check.inspection.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.Image

/**
 * 图像预处理工具类
 * - YUV → 灰度像素提取（零 Bitmap 开销）
 * - 对比度增强（Bitmap 级别）
 */
object ImageUtils {

    /**
     * 从 YUV_420_888 直接提取 Y 平面灰度像素
     * 返回 IntArray，每个元素为 [0, 255] 的灰度值
     * 不创建 Bitmap，性能最优，适合逐帧处理
     *
     * 兼容两种缓冲区类型：
     * - 堆缓冲区 (hasArray() == true)：直接读取数组
     * - 直接缓冲区 (DirectBuffer)：通过 ByteArray 中转读取
     */
    fun extractGrayscaleFromYuv(image: Image): IntArray? {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val stride = yPlane.rowStride
        val width = image.width
        val height = image.height

        if (buffer.hasArray()) {
            // 路径 1: 堆缓冲区 — 零拷贝直接访问
            val yData = buffer.array()
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val srcOffset = y * stride
                val dstOffset = y * width
                for (x in 0 until width) {
                    pixels[dstOffset + x] = yData[srcOffset + x].toInt() and 0xFF
                }
            }
            return pixels
        } else {
            // 路径 2: 直接缓冲区 — 复制到 ByteArray 后读取
            val rawBytes = ByteArray(buffer.remaining())
            buffer.mark()
            buffer.get(rawBytes)
            buffer.reset()

            val pixels = IntArray(width * height)
            var srcPos = 0
            for (y in 0 until height) {
                val dstOffset = y * width
                for (x in 0 until width) {
                    pixels[dstOffset + x] = rawBytes[srcPos + x].toInt() and 0xFF
                }
                srcPos += stride
            }
            return pixels
        }
    }

    /**
     * 对比度增强（Bitmap 级别）
     * 仅在需要时调用（例如识别率不足时启用预处理）
     *
     * @param bitmap 源位图
     * @param contrast 对比度系数，默认 1.8f（>1 增强，<1 减弱）
     */
    fun enhanceContrast(bitmap: Bitmap, contrast: Float = 1.8f): Bitmap {
        val colorMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, 0f,
            0f, contrast, 0f, 0f, 0f,
            0f, 0f, contrast, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, config)
        val canvas = Canvas(result)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        if (result != bitmap && !bitmap.isRecycled) {
            // 不自动回收，由调用方管理
        }
        return result
    }
}