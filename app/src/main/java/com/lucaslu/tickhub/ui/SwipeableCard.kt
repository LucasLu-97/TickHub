package com.lucaslu.tickhub.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Card with MIUI-style interactions:
 *  - swipe LEFT -> reveal red delete icon (vertically centered)
 *  - swipe RIGHT -> reveal a checkbox on the left, mark selected (card color unchanged)
 *  - long press + drag -> reorder (MIUI-like lift/shadow), via callbacks
 */
@Composable
fun SwipeableCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onSelect: ((Boolean) -> Unit)? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
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
    val shape = RoundedCornerShape(12.dp)

    Box(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // LEFT reveal: selection checkbox (aligned to vertical center of card)
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
                        com.lucaslu.tickhub.util.Haptics.tick(ctx)
                        scope.launch { offsetX.animateTo(if (it) selectWidthPx else 0f, spring(dampingRatio = 0.8f, stiffness = 420f)) }
                    }
                )
            }
        }
        // RIGHT reveal: delete icon (strict vertical center, independent of card height)
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
        // foreground card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), dragY.roundToInt()) }
                .then(if (dragging) Modifier.shadow(12.dp, shape, clip = true) else Modifier)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = when {
                                offsetX.value < -deleteWidthPx * 0.4f -> -deleteWidthPx
                                onSelect != null && offsetX.value > selectWidthPx * 0.4f -> selectWidthPx
                                else -> 0f
                            }
                            com.lucaslu.tickhub.util.Haptics.tick(ctx)
                            revealed = target < 0
                            selectRevealed = target > 0
                            if (target > 0) onSelect?.invoke(true)
                            scope.launch { offsetX.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val next = (offsetX.value + dragAmount).coerceIn(-deleteWidthPx, selectWidthPx)
                        scope.launch { offsetX.snapTo(next) }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            when {
                                revealed -> {
                                    revealed = false
                                    scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                                }
                                selectRevealed -> {
                                    selectRevealed = false
                                    onSelect?.invoke(false)
                                    scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                                }
                                else -> onClick?.invoke()
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            com.lucaslu.tickhub.util.Haptics.tick(ctx)
                            onDragStart?.invoke()
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            onDragDelta?.invoke(delta.y)
                        },
                        onDragEnd = { onDragEnd?.invoke() },
                        onDragCancel = { onDragEnd?.invoke() }
                    )
                },
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            content()
        }
    }
}
