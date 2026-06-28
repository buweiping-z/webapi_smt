package com.machine_check.inspection.ui.components

import com.google.zxing.Binarizer
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitArray
import com.google.zxing.common.BitMatrix

/**
 * 灰度亮度源 — 适配 ZXing 的 LuminanceSource
 */
class GrayscaleLuminanceSource(
    private val pixels: IntArray,
    width: Int,
    height: Int
) : LuminanceSource(width, height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height) { "Requested row is outside the image: $y" }
        val rowData = row ?: ByteArray(width)
        for (x in 0 until width) {
            rowData[x] = (pixels[y * width + x] and 0xFF).toByte()
        }
        return rowData
    }

    override fun getMatrix(): ByteArray {
        val matrix = ByteArray(width * height)
        for (i in pixels.indices) {
            matrix[i] = (pixels[i] and 0xFF).toByte()
        }
        return matrix
    }
}

/**
 * 恒等二值化器 — 直接使用预计算的 BitMatrix
 * 继承 Binarizer，跳过 ZXing 内置二值化
 */
class IdentityBinarizer(
    source: LuminanceSource,
    private val bitMatrix: BitMatrix
) : Binarizer(source) {

    override fun getBlackRow(y: Int, row: BitArray?): BitArray {
        val result = row ?: BitArray(bitMatrix.width)
        for (x in 0 until bitMatrix.width) {
            if (bitMatrix.get(x, y)) result.set(x)
        }
        return result
    }

    override fun getBlackMatrix(): BitMatrix = bitMatrix

    override fun createBinarizer(source: LuminanceSource): Binarizer {
        return IdentityBinarizer(source, bitMatrix)
    }
}
