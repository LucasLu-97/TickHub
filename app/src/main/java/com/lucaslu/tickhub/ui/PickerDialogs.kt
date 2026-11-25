package com.lucaslu.tickhub.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import com.lucaslu.tickhub.domain.RuleEngine

/** Time picker in a dialog (hour:minute wheels), avoids occupying the edit page. */
@Composable
fun TimePickerDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val parts = initial.split(":")
    var h by remember { mutableStateOf((parts.getOrNull(0)?.toIntOrNull() ?: 8).coerceIn(0, 23)) }
    var m by remember { mutableStateOf((parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)) }
    val hours = (0..23).map { it.toString().padStart(2, '0') }
    val minutes = (0..59).map { it.toString().padStart(2, '0') }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择时间") },
        text = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WheelPicker(hours, h, { h = it }, Modifier.weight(1f))
                Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
                WheelPicker(minutes, m, { m = it }, Modifier.weight(1f))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm("${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}")
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Interval-days picker in a dialog (1-30). */
@Composable
fun IntervalDialog(initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var n by remember { mutableStateOf(initial.coerceIn(1, 30)) }
    val items = (1..30).map { it.toString() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("每隔多少天（1 表示每天）") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                WheelPicker(items, n - 1, { n = it + 1 }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(n) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Date picker in a dialog (year/month/day wheels). */
@Composable
fun DatePickerDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val parsed = RuleEngine.parseDate(initial) ?: today
    val years = (today.year..(today.year + 5)).map { it.toString() }
    val months = (1..12).map { it.toString() }
    var yi by remember { mutableStateOf((parsed.year - today.year).coerceIn(0, years.lastIndex)) }
    var mi by remember { mutableStateOf((parsed.monthValue - 1).coerceIn(0, 11)) }
    val maxDay = YearMonth.of(today.year + yi, mi + 1).lengthOfMonth()
    var di by remember { mutableStateOf((parsed.dayOfMonth - 1).coerceIn(0, maxDay - 1)) }
    val days = (1..maxDay).map { it.toString() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择日期") },
        text = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WheelPicker(years, yi, { yi = it }, Modifier.weight(1.2f))
                WheelPicker(months, mi, { mi = it }, Modifier.weight(0.8f))
                WheelPicker(days, di, { di = it }, Modifier.weight(0.8f))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val y = today.year + yi
                val mo = mi + 1
                val d = (di + 1).coerceAtMost(YearMonth.of(y, mo).lengthOfMonth())
                onConfirm("$y-${mo.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}")
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
