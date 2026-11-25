package com.lucaslu.tickhub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
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
            3 -> drawSettings(color, w)
            else -> drawLog(color, w)
        }
    }
}

/** Minimal trash bin: lid line + U-shaped body, drawn centered. */
@Composable
fun DeleteLineIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(26.dp)) {
        val w = 2.dp.toPx()
        val cx = size.width / 2
        val cy = size.height / 2
        val halfW = size.width * 0.22f
        val lidY = cy - size.height * 0.22f
        val topY = cy - size.height * 0.14f
        val botY = cy + size.height * 0.28f
        // lid
        drawLine(color = tint, start = Offset(cx - halfW, lidY), end = Offset(cx + halfW, lidY), strokeWidth = w)
        // body: left, bottom, right (open top = minimal)
        drawLine(color = tint, start = Offset(cx - halfW * 0.8f, topY), end = Offset(cx - halfW * 0.8f, botY), strokeWidth = w)
        drawLine(color = tint, start = Offset(cx - halfW * 0.8f, botY), end = Offset(cx + halfW * 0.8f, botY), strokeWidth = w)
        drawLine(color = tint, start = Offset(cx + halfW * 0.8f, botY), end = Offset(cx + halfW * 0.8f, topY), strokeWidth = w)
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
    // alarm clock = rule/schedule: circle face + two bells + hands
    val cx = size.width / 2
    val cy = size.height * 0.55f
    val r = minOf(size.width, size.height) * 0.30f
    drawCircle(color = c, radius = r, center = Offset(cx, cy), style = Stroke(w))
    drawLine(color = c, start = Offset(cx, cy), end = Offset(cx, cy - r * 0.55f), strokeWidth = w)
    drawLine(color = c, start = Offset(cx, cy), end = Offset(cx + r * 0.45f, cy), strokeWidth = w)
    // bells (two short arcs at top)
    val bellR = r * 0.32f
    drawArc(color = c, startAngle = 200f, sweepAngle = 180f, useCenter = false, topLeft = Offset(cx - r * 0.72f - bellR, cy - r * 0.72f - bellR), size = Size(bellR * 2, bellR * 2), style = Stroke(w))
    drawArc(color = c, startAngle = 200f, sweepAngle = 180f, useCenter = false, topLeft = Offset(cx + r * 0.72f - bellR, cy - r * 0.72f - bellR), size = Size(bellR * 2, bellR * 2), style = Stroke(w))
    // feet
    drawLine(color = c, start = Offset(cx - r * 0.6f, cy + r * 0.85f), end = Offset(cx - r * 0.35f, cy + r * 0.6f), strokeWidth = w)
    drawLine(color = c, start = Offset(cx + r * 0.6f, cy + r * 0.85f), end = Offset(cx + r * 0.35f, cy + r * 0.6f), strokeWidth = w)
}

private fun DrawScope.drawHistory(c: Color, w: Float) {
    // counter-clockwise arrow around a dot = history/rewind
    val cx = size.width / 2
    val cy = size.height / 2
    val r = minOf(size.width, size.height) * 0.32f
    drawArc(color = c, startAngle = 40f, sweepAngle = 290f, useCenter = false, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2), style = Stroke(w))
    // arrow head at the arc start (upper-left)
    val a = 40f * kotlin.math.PI.toFloat() / 180f
    val ax = cx + r * cos(a)
    val ay = cy + r * sin(a)
    drawLine(color = c, start = Offset(ax, ay), end = Offset(ax - r * 0.28f, ay - r * 0.05f), strokeWidth = w)
    drawLine(color = c, start = Offset(ax, ay), end = Offset(ax + r * 0.05f, ay - r * 0.30f), strokeWidth = w)
    // center clock hand
    drawLine(color = c, start = Offset(cx, cy), end = Offset(cx, cy - r * 0.45f), strokeWidth = w)
    drawLine(color = c, start = Offset(cx, cy), end = Offset(cx + r * 0.30f, cy), strokeWidth = w)
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
    // standard gear: 8-tooth outline + center hole
    val cx = size.width / 2
    val cy = size.height / 2
    val outer = minOf(size.width, size.height) * 0.40f
    val inner = outer * 0.72f
    val teeth = 8
    val p = Path()
    for (i in 0 until teeth * 4) {
        val ang = (i * 360f / (teeth * 4) - 90f) * kotlin.math.PI.toFloat() / 180f
        val rad = if (i % 4 == 0 || i % 4 == 3) outer else inner
        val x = cx + rad * cos(ang)
        val y = cy + rad * sin(ang)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color = c, style = Stroke(w))
    drawCircle(color = c, radius = outer * 0.30f, center = Offset(cx, cy), style = Stroke(w))
}
