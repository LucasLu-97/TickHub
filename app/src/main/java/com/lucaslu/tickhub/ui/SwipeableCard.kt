package com.lucaslu.tickhub.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Card with MIUI-style interactions. Reordering is handled by Calvin Reorderable
 * via [dragHandle] (a longPressDraggableHandle built in ReorderableItem scope).
 *  - swipe LEFT -> reveal red delete icon (vertically centered)
 *  - swipe RIGHT -> select; states exclusive; [autoSelected] drives select-all
 *  - while dragging, reveal layers hidden and card lifted
 */
@Composable
fun SwipeableCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onSelect: ((Boolean) -> Unit)? = null,
    selected: Boolean = false,
    autoSelected: Boolean = false,
    dragging: Boolean = false,
    dragHandle: Modifier = Modifier,
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

    // select-all from outside: slide every card right into selected state
    LaunchedEffect(autoSelected) {
        if (onSelect != null) {
            revealed = false
            selectRevealed = autoSelected
            offsetX.animateTo(
                if (autoSelected) selectWidthPx else 0f,
                spring(dampingRatio = 0.8f, stiffness = 420f)
            )
        }
    }

    Box(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                modifier = Modifier.align(Alignment.CenterEnd).width(deleteWidth),
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
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .graphicsLayer { alpha = if (dragging) 0.97f else 1f }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
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
                            if (target == 0f && wasSelected) onSelect?.invoke(false)
                            scope.launch { offsetX.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                        }
                    ) { change, dragAmount ->
                        val minX = if (selectRevealed) 0f else -deleteWidthPx
                        val maxX = if (revealed) 0f else selectWidthPx
                        val next = (offsetX.value + dragAmount).coerceIn(minX, maxX)
                        scope.launch { offsetX.snapTo(next) }
                        change.consume()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (revealed || selectRevealed) {
                                revealed = false
                                selectRevealed = false
                                onSelect?.invoke(false)
                                scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                            } else {
                                onClick?.invoke()
                            }
                        }
                    )
                },
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 0.dp)
        ) {
            // drag handle lives on an inner element (as in the official demo), so it
            // doesn't fight the card's horizontal-swipe / tap pointer inputs
            Box(modifier = Modifier.fillMaxWidth().then(dragHandle)) {
                content()
            }
        }
    }
}
