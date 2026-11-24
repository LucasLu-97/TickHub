package com.lucaslu.tickhub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal line-art icons for the bottom navigation, drawn with Canvas to avoid
 * pulling in the large material-icons-extended dependency.
 */
@Composable
fun TabIcon(index: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = 2.dp.toPx()
        when (index) {
            0 -> drawRepo(color, w)
            1 -> drawClock(color, w)
            2 -> drawHistory(color, w)
            3 -> drawLog(color, w)
            4 -> drawSettings(color, w)
        }
    }
}

private fun DrawScope.drawRepo(c: Color, w: Float) {
    val left = size.width * 0.15f
    val right = size.width * 0.85f
    val top = size.height * 0.30f
    val bottom = size.height * 0.78f
    drawPath(
        path = Path().apply {
            moveTo(left, top)
            lineTo(size.width * 0.42f, top)
            lineTo(size.width * 0.5f, top + size.height * 0.12f)
            lineTo(right, top + size.height * 0.12f)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        },
        color = c,
        style = Stroke(w)
    )
}

private fun DrawScope.drawClock(c: Color, w: Float) {
    val cx = size.width / 2
    val cy = size.height / 2
    val r = minOf(size.width, size.height) * 0.36f
    drawCircle(Offset(cx, cy), r, color = c, style = Stroke(w))
    drawLine(color = c, start = Offset(cx, cy), end = Offset(cx, cy - r * 0.6f), strokeWidth = w)
    drawLine(color = c, start = Offset(cx, cy), end = Offset(cx + r * 0.5f, cy), strokeWidth = w)
}

private fun DrawScope.drawHistory(c: Color, w: Float) {
    val cx = size.width / 2
    val cy = size.height / 2
    val r = minOf(size.width, size.height) * 0.36f
    drawArc(
        color = c, startAngle = 30f, sweepAngle = 300f, useCenter = false,
        topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2), style = Stroke(w)
    )
    drawLine(color = c, start = Offset(cx, cy), end = Offset(cx, cy - r * 0.55f), strokeWidth = w)
    drawLine(color = c, start = Offset(cx, cy), end = Offset(cx + r * 0.45f, cy + r * 0.2f), strokeWidth = w)
}

private fun DrawScope.drawLog(c: Color, w: Float) {
    val left = size.width * 0.25f
    val top = size.height * 0.18f
    val right = size.width * 0.75f
    val bottom = size.height * 0.82f
    drawRect(color = c, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = Stroke(w))
    val lineX = left + (right - left) * 0.28f
    for (i in 0..2) {
        val y = top + (bottom - top) * (0.3f + i * 0.2f)
        drawLine(color = c, start = Offset(lineX, y), end = Offset(right - (right - left) * 0.15f, y), strokeWidth = w)
    }
}

private fun DrawScope.drawSettings(c: Color, w: Float) {
    val cx = size.width / 2
    val cy = size.height / 2
    val r = minOf(size.width, size.height) * 0.36f
    drawCircle(Offset(cx, cy), r * 0.32f, color = c, style = Stroke(w))
    for (i in 0..7) {
        val angle = i * 45f * kotlin.math.PI.toFloat() / 180f
        val sx = cx + cos(angle) * r * 0.55f
        val sy = cy + sin(angle) * r * 0.55f
        val ex = cx + cos(angle) * r * 0.82f
        val ey = cy + sin(angle) * r * 0.82f
        drawLine(color = c, start = Offset(sx, sy), end = Offset(ex, ey), strokeWidth = w)
    }
}
