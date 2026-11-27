package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.util.t

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.data.TriggerLog
import com.lucaslu.tickhub.data.TriggerStatus
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val logs by vm.logs.collectAsState(initial = emptyList())
    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                title = { Text(t("触发历史")) },
                actions = {
                    if (multiSelect) {
                        TextButton(onClick = {
                            com.lucaslu.tickhub.util.Haptics.tick(context)
                            selectedIds = if (selectedIds.size == logs.size) emptySet() else logs.map { it.id }.toSet()
                        }) { Text(t("全选")) }
                        TextButton(onClick = {
                            com.lucaslu.tickhub.util.Haptics.tick(context)
                            selectedIds.forEach { vm.deleteLog(it) }
                            selectedIds = emptySet(); multiSelect = false
                        }) { Text(t("删除")) }
                        TextButton(onClick = { com.lucaslu.tickhub.util.Haptics.tick(context); multiSelect = false; selectedIds = emptySet() }) { Text(t("取消")) }
                    }
                }
            )
        }
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Text(t("最近 ") + "${logs.size}" + t(" 条记录"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            if (logs.isEmpty()) {
                Text(t("还没有触发记录。创建规则并到点后，记录会显示在这里。"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    SwipeableCard(
                        modifier = Modifier.fillMaxWidth(),
                        onDelete = { vm.deleteLog(log.id) },
                        onClick = {
                            if (multiSelect) {
                                selectedIds = if (selectedIds.contains(log.id)) selectedIds - log.id else selectedIds + log.id
                            }
                        },
                        onSelect = { sel ->
                            multiSelect = true
                            selectedIds = if (sel) selectedIds + log.id else selectedIds - log.id
                        },
                        selected = selectedIds.contains(log.id),
                        autoSelected = multiSelect && selectedIds.contains(log.id),
                        // history is timeline-ordered: no drag handle; long-press only dims the card
                        content = { LogCardContent(log) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LogCardContent(log: TriggerLog) {
    val (label, color) = if (log.tracking) {
        t("执行中") to MaterialTheme.colorScheme.tertiary
    } else when (log.status) {
        TriggerStatus.SUCCESS -> t("成功") to MaterialTheme.colorScheme.primary
        TriggerStatus.MAKEUP -> t("补触发") to MaterialTheme.colorScheme.tertiary
        TriggerStatus.FAILED -> t("失败") to MaterialTheme.colorScheme.error
        TriggerStatus.SKIPPED -> t("跳过") to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        androidx.compose.foundation.layout.Row {
            val names = log.workflowNames.ifEmpty { listOfNotNull(log.workflowName.takeIf { it.isNotBlank() }) }
            Text(
                if (names.isEmpty()) t("工作流") else names.joinToString("、"),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        }
        if (log.repoFullName.isNotBlank()) {
            Text(log.repoFullName.split("/").lastOrNull() ?: log.repoFullName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(t("计划：") + fmt.format(Date(log.plannedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            log.firedAt?.let { Text(t("实际：") + fmt.format(Date(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
                ?: Spacer(Modifier.weight(1f))
            if (log.tracking) {
                // plain live-growing duration text — no spinner on history cards
                ElapsedText(startAt = log.firedAt ?: log.plannedAt, prefix = t("耗时 "))
            } else if (log.durationMs > 0) {
                val s = log.durationMs / 1000
                Text(
                    t("耗时 ") + "${(s / 60).toString().padStart(2, '0')}m${(s % 60).toString().padStart(2, '0')}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        log.errorMessage?.let {
            val shown = if (log.errorKey != null) com.lucaslu.tickhub.util.L10n.tFormat(log.errorKey!!, log.errorArgs) else com.lucaslu.tickhub.util.L10n.tNow(it)
            Text(shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
