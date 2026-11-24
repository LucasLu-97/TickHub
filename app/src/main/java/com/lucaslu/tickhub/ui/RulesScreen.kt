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
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.data.RuleType
import com.lucaslu.tickhub.data.WorkflowItem
import com.lucaslu.tickhub.domain.RuleEngine

private val TYPE_LABELS = mapOf(
    RuleType.ONCE to "一次",
    RuleType.DAILY to "每天",
    RuleType.WEEKDAYS to "每工作日",
    RuleType.WEEKLY to "每周",
    RuleType.EVERY_N_DAYS to "每N天"
)

@Composable
fun RulesScreen(vm: AppViewModel) {
    val rules by vm.rules.collectAsState(initial = emptyList())
    val snapshot by vm.snapshot.collectAsState(initial = com.lucaslu.tickhub.data.ScanSnapshot())

    var editing by remember { mutableStateOf<Rule?>(null) }
    var creating by remember { mutableStateOf(false) }

    val selectedWorkflows = snapshot.workflows.filter { it.selected }
    val repoNames = snapshot.repos.associateBy { it.id }

    if (creating || editing != null) {
        RuleEditScreen(
            workflows = selectedWorkflows,
            repoNames = repoNames.mapValues { it.value.fullName },
            existing = editing,
            onSave = { rule ->
                vm.saveRule(rule)
                creating = false
                editing = null
            },
            onCancel = { creating = false; editing = null }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("触发规则", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { creating = true }, enabled = selectedWorkflows.isNotEmpty()) { Text("新建") }
        }
        if (selectedWorkflows.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("请先在「仓库」页勾选要纳管的工作流，再来创建规则。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    onToggle = { vm.setRuleEnabled(rule.id, it) },
                    onEdit = { editing = rule },
                    onDelete = { vm.deleteRule(rule.id) },
                    onTriggerNow = { vm.triggerNow(rule) }
                )
            }
        }
    }
}

@Composable
private fun RuleCard(rule: Rule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onTriggerNow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(rule.workflowName.ifBlank { "工作流 #${rule.workflowId}" }, style = MaterialTheme.typography.titleSmall)
            Text(rule.repoFullName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val timeText = String.format("%02d:%02d", rule.hour, rule.minute)
            val cycleText = when (rule.type) {
                RuleType.ONCE -> "一次 · ${rule.onceDate ?: "-"}"
                RuleType.DAILY -> "每天"
                RuleType.WEEKDAYS -> "每工作日"
                RuleType.WEEKLY -> "每周 ${rule.weekdays.joinToString(",")}"
                RuleType.EVERY_N_DAYS -> "每 ${rule.intervalDays} 天"
            }
            Text("$cycleText · $timeText", style = MaterialTheme.typography.bodyMedium)
            rule.nextFireAt?.let {
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
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("启用", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onTriggerNow) { Text("立即触发") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}
