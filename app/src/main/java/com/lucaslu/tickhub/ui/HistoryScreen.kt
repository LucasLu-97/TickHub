package com.lucaslu.tickhub.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.data.TriggerLog
import com.lucaslu.tickhub.data.TriggerStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val logs by vm.logs.collectAsState(initial = emptyList())
    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("触发历史", style = MaterialTheme.typography.titleLarge)
                Text("最近 ${logs.size} 条记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (multiSelect) {
                TextButton(onClick = {
                    selectedIds = if (selectedIds.size == logs.size) emptySet() else logs.map { it.id }.toSet()
                }) { Text("全选") }
                TextButton(onClick = {
                    selectedIds.forEach { vm.deleteLog(it) }
                    selectedIds = emptySet(); multiSelect = false
                }) { Text("删除") }
                TextButton(onClick = { multiSelect = false; selectedIds = emptySet() }) { Text("取消") }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (logs.isEmpty()) {
            Text("还没有触发记录。创建规则并到点后，记录会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs, key = { it.id }) { log ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (multiSelect) {
                        Checkbox(
                            checked = selectedIds.contains(log.id),
                            onCheckedChange = {
                                selectedIds = if (it) selectedIds + log.id else selectedIds - log.id
                            }
                        )
                    }
                    SwipeableCard(
                        modifier = Modifier.weight(1f),
                        onDelete = { vm.deleteLog(log.id) },
                        onLongClick = {
                            multiSelect = true
                            selectedIds = selectedIds + log.id
                        },
                        selected = selectedIds.contains(log.id),
                        content = { LogCardContent(log) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LogCardContent(log: TriggerLog) {
    val (label, color) = when (log.status) {
        TriggerStatus.SUCCESS -> "成功" to MaterialTheme.colorScheme.primary
        TriggerStatus.MAKEUP -> "补触发" to MaterialTheme.colorScheme.tertiary
        TriggerStatus.FAILED -> "失败" to MaterialTheme.colorScheme.error
        TriggerStatus.SKIPPED -> "跳过" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row {
            val names = log.workflowNames.ifEmpty { listOfNotNull(log.workflowName.takeIf { it.isNotBlank() }) }
            Text(
                if (names.isEmpty()) "工作流" else names.joinToString("、"),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (log.durationMs > 0) {
                val s = log.durationMs / 1000
                Text(
                    "${(s / 60).toString().padStart(2, '0')}m${(s % 60).toString().padStart(2, '0')}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        }
        if (log.repoFullName.isNotBlank()) {
            Text(log.repoFullName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text("计划：${fmt.format(Date(log.plannedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        log.firedAt?.let { Text("实际：${fmt.format(Date(it))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        log.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
