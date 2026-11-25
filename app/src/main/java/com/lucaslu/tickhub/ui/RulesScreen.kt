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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.data.RuleType
import com.lucaslu.tickhub.domain.RuleEngine

@Suppress("DEPRECATION")
private val TYPE_LABELS = mapOf(
    RuleType.ONCE to "单次",
    RuleType.DAILY to "每天",
    RuleType.WEEKLY to "每周",
    RuleType.LEGAL_WORKDAY to "法定工作日",
    RuleType.LEGAL_HOLIDAY to "法定节假日",
    RuleType.EVERY_N_DAYS to "每隔X天",
    RuleType.WEEKDAYS to "法定工作日"
)

@Composable
fun RulesScreen(vm: AppViewModel) {
    val rules by vm.rules.collectAsState(initial = emptyList())
    val snapshot by vm.snapshot.collectAsState(initial = com.lucaslu.tickhub.data.ScanSnapshot())
    val running by vm.runningExecutions.collectAsState(initial = emptyMap())
    val showExec by vm.showExecutionStatus.collectAsState(initial = true)

    var editing by remember { mutableStateOf<Rule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val selectedWorkflows = snapshot.workflows.filter { it.selected }
    val repoNames = snapshot.repos.associateBy { it.id }

    if (creating || editing != null) {
        RuleEditScreen(
            workflows = selectedWorkflows,
            repoNames = repoNames.mapValues { it.value.fullName },
            existing = editing,
            onSave = { rules ->
                // editing a single-repo rule keeps its id; multi-repo splits get fresh ids
                val finalRules = if (editing != null && rules.size == 1) {
                    listOf(rules.first().copy(id = editing!!.id))
                } else rules
                finalRules.forEach { vm.saveRule(it) }
                if (editing != null && rules.size > 1) vm.deleteRule(editing!!.id)
                creating = false; editing = null
            },
            onCancel = { creating = false; editing = null }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("触发规则", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (showExec && running.isNotEmpty()) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            }
            if (multiSelect) {
                TextButton(onClick = {
                    selectedIds = if (selectedIds.size == rules.size) emptySet() else rules.map { it.id }.toSet()
                }) { Text("全选") }
                TextButton(onClick = {
                    selectedIds.forEach { vm.deleteRule(it) }
                    selectedIds = emptySet(); multiSelect = false
                }) { Text("删除") }
                TextButton(onClick = { multiSelect = false; selectedIds = emptySet() }) { Text("取消") }
            } else {
                Button(onClick = { creating = true }, enabled = selectedWorkflows.isNotEmpty()) { Text("新建") }
            }
        }
        if (selectedWorkflows.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("请先在「仓库」页勾选要纳管的工作流，再来创建规则。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        // group by repo
        val grouped = rules.groupBy { it.repoFullName.ifBlank { "未分类" } }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            grouped.forEach { (repoName, repoRules) ->
                item(key = "header-$repoName") {
                    Text(
                        repoName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                items(repoRules, key = { it.id }) { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (multiSelect) {
                            Checkbox(
                                checked = selectedIds.contains(rule.id),
                                onCheckedChange = {
                                    selectedIds = if (it) selectedIds + rule.id else selectedIds - rule.id
                                }
                            )
                        }
                        SwipeableCard(
                            modifier = Modifier.weight(1f),
                            onDelete = { vm.deleteRule(rule.id) },
                            onLongClick = {
                                multiSelect = true
                                selectedIds = selectedIds + rule.id
                            },
                            selected = selectedIds.contains(rule.id),
                            content = {
                                RuleCardContent(
                                    rule = rule,
                                    onToggle = { vm.setRuleEnabled(rule.id, it) },
                                    onEdit = { editing = rule },
                                    onTriggerNow = { vm.triggerNow(rule) },
                                    runningStart = if (showExec) running[rule.id] else null
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleCardContent(rule: Rule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onTriggerNow: () -> Unit, runningStart: Long? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        val names = rule.workflowNames.ifEmpty { listOf(rule.workflowName.ifBlank { "工作流 #${rule.workflowId}" }) }
        Text(names.joinToString("、"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        val timeText = String.format("%02d:%02d", rule.hour, rule.minute)
        val cycleText = TYPE_LABELS[rule.type] ?: rule.type.name
        Text("$cycleText · $timeText", style = MaterialTheme.typography.bodyMedium)
        rule.effectiveFireAt?.let {
            val next = RuleEngine.fromEpochMillis(it)
            val drift = rule.driftMinutes
            val driftText = if (drift > 0) "（随机漂移 ≤${drift} 分钟）" else ""
            Text(
                "下次：${RuleEngine.formatDate(next.toLocalDate())} ${String.format("%02d:%02d", next.hour, next.minute)}$driftText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } ?: rule.nextFireAt?.let {
            val next = RuleEngine.fromEpochMillis(it)
            Text(
                "下次：${RuleEngine.formatDate(next.toLocalDate())} ${String.format("%02d:%02d", next.hour, next.minute)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = rule.status == RuleStatus.ENABLED,
                onCheckedChange = { onToggle(it); com.lucaslu.tickhub.util.Haptics.tick(context) },
                modifier = Modifier.padding(end = 8.dp).graphicsLayer(scaleY = 0.72f)
            )
            Text("启用", style = MaterialTheme.typography.labelMedium)
            if (runningStart != null) {
                Spacer(Modifier.padding(4.dp))
                RunningIndicator(startAt = runningStart)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onTriggerNow) { Text("立即触发") }
            TextButton(onClick = onEdit) { Text("编辑") }
        }
    }
}
