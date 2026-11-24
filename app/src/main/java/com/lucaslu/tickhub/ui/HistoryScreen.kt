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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("触发历史", style = MaterialTheme.typography.titleLarge)
        Text("最近 ${logs.size} 条记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        if (logs.isEmpty()) {
            Text("还没有触发记录。创建规则并到点后，记录会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs, key = { it.id }) { log -> LogCard(log) }
        }
    }
}

@Composable
private fun LogCard(log: TriggerLog) {
    val (label, color) = when (log.status) {
        TriggerStatus.SUCCESS -> "成功" to MaterialTheme.colorScheme.primary
        TriggerStatus.MAKEUP -> "补触发" to MaterialTheme.colorScheme.tertiary
        TriggerStatus.FAILED -> "失败" to MaterialTheme.colorScheme.error
        TriggerStatus.SKIPPED -> "跳过" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(log.workflowName.ifBlank { "工作流" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(label, color = color, style = MaterialTheme.typography.labelLarge)
            }
            if (log.repoFullName.isNotBlank()) {
                Text(log.repoFullName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text("计划：${fmt.format(Date(log.plannedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            log.firedAt?.let { Text("实际：${fmt.format(Date(it))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            log.httpCode?.let { Text("HTTP $it", style = MaterialTheme.typography.labelSmall) }
            log.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
