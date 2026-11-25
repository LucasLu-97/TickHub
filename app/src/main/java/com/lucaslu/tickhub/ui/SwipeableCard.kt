package com.lucaslu.tickhub.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Card with two-stage swipe-to-delete:
 *  - swipe left past threshold and release -> delete icon stays visible (no auto delete)
 *  - tap the icon to confirm delete, or tap card to cancel
 * Long-click enters multi-select; selected cards get a highlighted background.
 * Uses ONE Card so the whole surface highlights; callers must not wrap in another Card.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SwipeableCard(
    onDelete: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    var deleteArmed by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { deleteArmed = true; false }
                SwipeToDismissBoxValue.StartToEnd -> { deleteArmed = false; false }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )
    LaunchedEffect(deleteArmed) {
        if (deleteArmed) dismissState.snapTo(SwipeToDismissBoxValue.EndToStart)
    }

    val revealColor by animateColorAsState(
        if (deleteArmed) MaterialTheme.colorScheme.errorContainer else Color.Transparent
    )
    val cardColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(revealColor).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                DeleteLineIcon(tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .then(
                    if (onLongClick != null) Modifier.combinedClickable(
                        onClick = { if (deleteArmed) { deleteArmed = false; dismissState.reset() } },
                        onLongClick = onLongClick
                    ) else Modifier
                ),
            colors = CardDefaults.cardColors(containerColor = cardColor)
        ) {
            Box {
                content()
                if (deleteArmed) {
                    IconButton(
                        onClick = { onDelete() },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(44.dp)
                    ) {
                        DeleteLineIcon(tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
