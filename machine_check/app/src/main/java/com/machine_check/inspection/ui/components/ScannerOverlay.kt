package com.machine_check.inspection.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/**
 * 扫码取景框覆盖层
 * - 四角边框
 * - 中间区域透明，四周半透明遮罩
 * - 动态扫描线上下移动
 */
@Composable
fun ScannerOverlay(modifier: Modifier = Modifier, isActive: Boolean = true) {
    val scanLineProgress = rememberInfiniteTransition()
    val scanYOffset by scanLineProgress.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val cornerLength = 40.dp
    val strokeWidth = 3.dp
    val frameSizeFraction = 0.45f // 取景框占屏幕宽度的比例

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            // 取景框尺寸
            val frameW = canvasW * frameSizeFraction
            val frameH = frameW
            val frameX = (canvasW - frameW) / 2f
            val frameY = (canvasH - frameH) / 2f

            // 1. 绘制四周遮罩（取景框外部半透明黑色）
            drawRect(color = Color.Black.copy(alpha = 0.6f), size = size)
            // 2. 绘制取景框边框（四角）
            val cornerColor = Color(0xFF4CAF50)
            val cornerStroke = Stroke(width = strokeWidth.toPx())

            // 左上角
            drawLine(cornerColor, Offset(frameX, frameY + cornerLength.toPx()), Offset(frameX, frameY), strokeWidth.toPx())
            drawLine(cornerColor, Offset(frameX, frameY), Offset(frameX + cornerLength.toPx(), frameY), strokeWidth.toPx())
            // 右上角
            drawLine(cornerColor, Offset(frameX + frameW, frameY + cornerLength.toPx()), Offset(frameX + frameW, frameY), strokeWidth.toPx())
            drawLine(cornerColor, Offset(frameX + frameW, frameY), Offset(frameX + frameW - cornerLength.toPx(), frameY), strokeWidth.toPx())
            // 左下角
            drawLine(cornerColor, Offset(frameX, frameY + frameH - cornerLength.toPx()), Offset(frameX, frameY + frameH), strokeWidth.toPx())
            drawLine(cornerColor, Offset(frameX, frameY + frameH), Offset(frameX + cornerLength.toPx(), frameY + frameH), strokeWidth.toPx())
            // 右下角
            drawLine(cornerColor, Offset(frameX + frameW, frameY + frameH - cornerLength.toPx()), Offset(frameX + frameW, frameY + frameH), strokeWidth.toPx())
            drawLine(cornerColor, Offset(frameX + frameW, frameY + frameH), Offset(frameX + frameW - cornerLength.toPx(), frameY + frameH), strokeWidth.toPx())

            // 3. 绘制扫描线
            if (isActive) {
                val lineY = frameY + (frameH * scanYOffset)
                drawLine(
                    color = Color(0xFF4CAF50).copy(alpha = 0.8f),
                    start = Offset(frameX + 10.dp.toPx(), lineY),
                    end = Offset(frameX + frameW - 10.dp.toPx(), lineY),
                    strokeWidth = 2.dp.toPx()
                )
                // 扫描线渐变光晕
                drawLine(
                    color = Color(0xFF4CAF50).copy(alpha = 0.3f),
                    start = Offset(frameX + 5.dp.toPx(), lineY),
                    end = Offset(frameX + frameW - 5.dp.toPx(), lineY + 20.dp.toPx()),
                    strokeWidth = 10.dp.toPx()
                )
            }
        }

        // 底部提示文字
        Text(
            text = "将条码对准取景框",
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            color = Color.White.copy(alpha = 0.9f),
            style = TextStyle(fontSize = 14.sp)
        )
    }
}
