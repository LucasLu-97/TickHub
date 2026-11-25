package com.lucaslu.tickhub.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Card with MIUI-style interactions, all gestures handled in ONE pointerInput
 * so horizontal swipes don't consume events needed by drag-to-reorder:
 *  - swipe LEFT -> reveal red delete icon (vertically centered)
 *  - swipe RIGHT -> reveal a checkbox, mark selected (card color unchanged)
 *  - long press + drag -> lift & follow finger (via callbacks); press alone -> "pressed" dim
 */
@Composable
fun SwipeableCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onSelect: ((Boolean) -> Unit)? = null,
    selected: Boolean = false,
    dragY: Float = 0f,
    dragging: Boolean = false,
    onDragStart: (() -> Unit)? = null,
    onDragDelta: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val deleteWidth = 76.dp
    val selectWidth = 56.dp
    val deleteWidthPx = with(LocalDensity.current) { deleteWidth.toPx() }
    val selectWidthPx = with(LocalDensity.current) { selectWidth.toPx() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var revealed by remember { mutableStateOf(false) }
    var selectRevealed by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val canReorder = onDragStart != null
    val slopPx = with(LocalDensity.current) { 10.dp.toPx() }

    Box(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // hide reveal layers while dragging so they don't stay behind the moved card
        if (!dragging) {
            if (onSelect != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(selectWidth)
                        .padding(start = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = {
                            onSelect(it)
                            selectRevealed = it
                            revealed = false
                            com.lucaslu.tickhub.util.Haptics.tick(ctx)
                            scope.launch { offsetX.animateTo(if (it) selectWidthPx else 0f, spring(dampingRatio = 0.8f, stiffness = 420f)) }
                        }
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(deleteWidth),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clickable {
                            com.lucaslu.tickhub.util.Haptics.tick(ctx)
                            onDelete()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    DeleteLineIcon(tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), dragY.roundToInt()) }
                .graphicsLayer {
                    alpha = if (dragging) 0.95f else 1f
                    scaleX = if (dragging) 1.02f else 1f
                    scaleY = if (dragging) 1.02f else 1f
                }
                .then(if (dragging) Modifier.shadow(14.dp, shape, clip = true) else Modifier)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downTime = System.currentTimeMillis()
                        var startX = down.position.x
                        var startY = down.position.y
                        var mode = 0 // 0 undecided, 1 horizontal swipe, 2 drag-reorder, 3 long-press-tap
                        val longPressMs = 260L

                        while (true) {
                            // wait for next pointer event, but no longer than the long-press window
                            val remaining = longPressMs - (System.currentTimeMillis() - downTime)
                            val event = if (mode == 0 && remaining > 0) {
                                kotlinx.coroutines.withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) }
                            } else {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }

                            if (event == null) {
                                // long press fired while finger held still
                                com.lucaslu.tickhub.util.Haptics.tick(ctx)
                                pressed = true
                                if (canReorder) {
                                    mode = 2
                                    onDragStart?.invoke()
                                } else {
                                    mode = 3
                                }
                                continue
                            }

                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY

                            if (mode == 0 && (abs(dx) > slopPx || abs(dy) > slopPx)) {
                                pressed = false
                                mode = if (abs(dx) > abs(dy)) 1 else 2
                                if (mode == 2 && canReorder) onDragStart?.invoke()
                                startX = change.position.x
                                startY = change.position.y
                                continue
                            }

                            when (mode) {
                                1 -> {
                                    change.consume()
                                    // state is exclusive: once selected, left-swipe can only reset;
                                    // once delete is revealed, right-swipe can only reset
                                    val minX = if (selectRevealed) 0f else -deleteWidthPx
                                    val maxX = if (revealed) 0f else selectWidthPx
                                    val next = (offsetX.value + dx).coerceIn(minX, maxX)
                                    scope.launch { offsetX.snapTo(next) }
                                    startX = change.position.x
                                }
                                2 -> {
                                    if (canReorder) {
                                        change.consume() // let list scroll on non-reorderable pages
                                        onDragDelta?.invoke(dy)
                                        startY = change.position.y
                                    }
                                }
                            }
                        }

                        pressed = false
                        when (mode) {
                            1 -> {
                                // snap: right-swipe selects, left-swipe reveals delete; opposite swipe only resets
                                val wasSelected = selectRevealed
                                val target = when {
                                    offsetX.value < -deleteWidthPx * 0.4f && !selectRevealed -> -deleteWidthPx
                                    offsetX.value > selectWidthPx * 0.4f && !revealed && onSelect != null -> selectWidthPx
                                    else -> 0f
                                }
                                com.lucaslu.tickhub.util.Haptics.tick(ctx)
                                revealed = target < 0
                                selectRevealed = target > 0
                                if (target > 0) onSelect?.invoke(true)
                                // reset to normal also clears selection (and thus multi-select actions)
                                if (target == 0f && wasSelected) onSelect?.invoke(false)
                                scope.launch { offsetX.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                            }
                            2 -> {
                                if (canReorder) onDragEnd?.invoke()
                            }
                            0 -> {
                                // tap
                                if (revealed || selectRevealed) {
                                    revealed = false
                                    selectRevealed = false
                                    onSelect?.invoke(false)
                                    scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                                } else {
                                    onClick?.invoke()
                                }
                            }
                            3 -> { /* long-press without reorder: no action (dimmed only) */ }
                        }
                    }
                },
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (pressed) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            content()
        }
    }
}
