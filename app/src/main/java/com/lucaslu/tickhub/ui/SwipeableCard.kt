package com.lucaslu.tickhub.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * QQ-style swipe card:
 *  - swipe LEFT and release -> card slides left, red delete icon stays revealed
 *  - tap the icon deletes; tap the card or swipe RIGHT restores
 *  - tap card -> onClick; long press -> onLongClick (multi-select)
 *  - selected state highlights the WHOLE rounded card (no rectangular ripple)
 */
@Composable
fun SwipeableCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    val deleteWidth = 76.dp
    val deleteWidthPx = with(LocalDensity.current) { deleteWidth.toPx() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var revealed by remember { mutableStateOf(false) }

    val cardColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer
    )

    Box(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))) {
        // delete icon revealed behind the card; its center is aligned to the card's vertical center
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .width(deleteWidth)
                    .fillMaxHeight()
                    .clickable {
                        com.lucaslu.tickhub.util.Haptics.tick(ctx)
                        onDelete()
                    },
                contentAlignment = Alignment.Center
            ) {
                DeleteLineIcon(
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        // foreground card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val shouldReveal = offsetX.value < -deleteWidthPx * 0.4f
                            revealed = shouldReveal
                            com.lucaslu.tickhub.util.Haptics.tick(ctx)
                            scope.launch {
                                offsetX.animateTo(if (shouldReveal) -deleteWidthPx else 0f, spring(dampingRatio = 0.75f, stiffness = 400f))
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val next = (offsetX.value + dragAmount).coerceIn(-deleteWidthPx, 0f)
                        scope.launch { offsetX.snapTo(next) }
                    }
                }
                .then(
                    if (onClick != null || onLongClick != null) Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                if (revealed) {
                                    revealed = false
                                    scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f)) }
                                } else {
                                    onClick?.invoke()
                                }
                            },
                            onLongPress = { onLongClick?.invoke() }
                        )
                    } else Modifier
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor)
        ) {
            content()
        }
    }
}
