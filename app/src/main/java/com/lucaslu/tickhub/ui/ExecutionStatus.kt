package com.lucaslu.tickhub.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Live elapsed-time label, ticks once per second only while composed (foreground). */
@Composable
fun ElapsedText(startAt: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val total = ((now - startAt) / 1000).coerceAtLeast(0)
    val mm = (total / 60).toString().padStart(2, '0')
    val ss = (total % 60).toString().padStart(2, '0')
    Text("${mm}m${ss}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

/** Small indeterminate spinner + elapsed time for a running rule. */
@Composable
fun RunningIndicator(startAt: Long, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        ElapsedText(startAt)
    }
}
