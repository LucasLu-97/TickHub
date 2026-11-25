package com.lucaslu.tickhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Generic drag-to-reorder controller for card lists.
 * Keeps an ordered copy of the data, handles lift/drag/drop offsets, and commits
 * the new order through [onCommit] when a drag finishes.
 */
class ReorderController<T>(
    private val keyOf: (T) -> String,
    private val itemHeightPx: Float,
    private val onCommit: (List<T>) -> Unit
) {
    val items = mutableStateListOf<T>()
    var draggingKey by mutableStateOf<String?>(null)
    var dragOffset by mutableFloatStateOf(0f)

    private var accumulated = 0f

    /** Sync from an external list while preserving the user-defined order. */
    fun syncFrom(source: List<T>) {
        val sourceKeys = source.map(keyOf).toSet()
        // drop items no longer present
        items.removeAll { keyOf(it) !in sourceKeys }
        // append brand-new items
        source.forEach { item ->
            if (items.none { keyOf(it) == keyOf(item) }) items.add(item)
        }
        // update in-place so data changes (e.g. status) are reflected
        source.forEach { incoming ->
            val idx = items.indexOfFirst { keyOf(it) == keyOf(incoming) }
            if (idx >= 0) items[idx] = incoming
        }
    }

    fun startDrag(key: String) {
        draggingKey = key
        accumulated = 0f
        dragOffset = 0f
    }

    fun drag(deltaY: Float) {
        val key = draggingKey ?: return
        accumulated += deltaY
        dragOffset = accumulated
        val idx = items.indexOfFirst { keyOf(it) == key }
        if (idx < 0) return
        val steps = (accumulated / itemHeightPx).toInt()
        if (steps != 0) {
            val target = (idx + steps).coerceIn(0, items.lastIndex)
            if (target != idx) {
                val moved = items.removeAt(idx)
                items.add(target, moved)
                accumulated -= steps * itemHeightPx
                dragOffset = accumulated
            }
        }
    }

    fun endDrag() {
        if (draggingKey != null) {
            draggingKey = null
            dragOffset = 0f
            accumulated = 0f
            onCommit(items.toList())
        }
    }

    fun offsetFor(key: String): Float = if (draggingKey == key) dragOffset else 0f
    fun isDragging(key: String): Boolean = draggingKey == key
}

@Composable
fun <T> rememberReorderController(
    keyOf: (T) -> String,
    itemHeightDp: androidx.compose.ui.unit.Dp = 120.dp,
    onCommit: (List<T>) -> Unit
): ReorderController<T> {
    val px = with(LocalDensity.current) { itemHeightDp.toPx() }
    return remember { ReorderController(keyOf, px, onCommit) }
}
