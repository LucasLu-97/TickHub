package com.lucaslu.tickhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * Alarm-clock style wheel picker. Fling decays naturally (rememberSnapFlingBehavior
 * gives the slow-down feel) and snaps to a row. The centered row is highlighted.
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 42.dp,
    visibleRows: Int = 5
) {
    if (items.isEmpty()) return
    val initial = selectedIndex.coerceIn(items.indices)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val viewportHeight = rowHeight * visibleRows
    val verticalPadding = (viewportHeight - rowHeight) / 2

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(listState) {
        var first = true
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                onSelectedChange(idx.coerceIn(items.indices))
                if (!first) com.lucaslu.tickhub.util.Haptics.tick(context)
                first = false
            }
    }

    Box(modifier = modifier.height(viewportHeight)) {
        // centered selection highlight
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(rowHeight)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
        )
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = verticalPadding),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size) { index ->
                val dist = abs(index - listState.firstVisibleItemIndex)
                val scale = when (dist) { 0 -> 1.25f; 1 -> 1f; else -> 0.85f }
                val alpha = when (dist) { 0 -> 1f; 1 -> 0.75f; else -> 0.45f }
                Box(
                    modifier = Modifier.fillMaxWidth().height(rowHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        items[index],
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
                    )
                }
            }
        }
    }
}
