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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var confirmTrigger by remember { mutableStateOf<Rule?>(null) }
    var orderLoaded by remember { mutableStateOf(false) }
    var repoOrder by remember { mutableStateOf<List<String>>(emptyList()) }

    val reorder = rememberReorderController<com.lucaslu.tickhub.data.Rule>(
        keyOf = { it.id },
        itemHeightDp = 112.dp,
        onCommit = { vm.saveOrder("rules", it.map { r -> r.id }) }
    )
    LaunchedEffect(Unit) {
        repoOrder = vm.readOrder("repos")
        val saved = vm.readOrder("rules")
        reorder.syncFrom(vm.applyOrder(rules, saved) { it.id })
        orderLoaded = true
    }
    LaunchedEffect(rules) {
        if (orderLoaded && reorder.draggingKey == null) reorder.syncFrom(rules)
    }

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

    confirmTrigger?.let { ruleToTrigger ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmTrigger = null },
            title = { Text("确认立即触发") },
            text = { Text("将立即触发「${ruleToTrigger.workflowNames.joinToString("、").ifBlank { ruleToTrigger.workflowName }}」，确定继续？") },
            confirmButton = {
                TextButton(onClick = { vm.triggerNow(ruleToTrigger); confirmTrigger = null }) { Text("确认触发") }
            },
            dismissButton = { TextButton(onClick = { confirmTrigger = null }) { Text("取消") } }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("触发规则")
                        if (showExec && running.isNotEmpty()) {
                            Spacer(Modifier.padding(6.dp))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                },
                actions = {
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
                        androidx.compose.material3.IconButton(onClick = { creating = true }, enabled = selectedWorkflows.isNotEmpty()) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.filled.Add,
                                contentDescription = "新建规则"
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
        if (selectedWorkflows.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("请先在「仓库」页勾选要纳管的工作流，再来创建规则。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        // group by repo, order follows the saved rule order (which follows repo order initially)
        val grouped = reorder.items.groupBy { it.repoFullName.split("/").lastOrNull().takeUnless { it.isNullOrBlank() } ?: "未分类" }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
        ) {
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
                    SwipeableCard(
                        modifier = Modifier.fillMaxWidth(),
                        onDelete = { vm.deleteRule(rule.id) },
                        onClick = {
                            if (multiSelect) {
                                selectedIds = if (selectedIds.contains(rule.id)) selectedIds - rule.id else selectedIds + rule.id
                            }
                        },
                        onSelect = { sel ->
                            multiSelect = true
                            selectedIds = if (sel) selectedIds + rule.id else selectedIds - rule.id
                        },
                        selected = selectedIds.contains(rule.id),
                        dragY = reorder.offsetFor(rule.id),
                        dragging = reorder.isDragging(rule.id),
                        onDragStart = { reorder.startDrag(rule.id) },
                        onDragDelta = { reorder.drag(it) },
                        onDragEnd = { reorder.endDrag() },
                        content = {
                            RuleCardContent(
                                rule = rule,
                                onToggle = { vm.setRuleEnabled(rule.id, it) },
                                onEdit = { editing = rule },
                                onTriggerNow = { confirmTrigger = rule },
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val names = rule.workflowNames.ifEmpty { listOf(rule.workflowName.ifBlank { "工作流 #${rule.workflowId}" }) }
        Text(names.joinToString("、"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        val timeText = rule.timeSlots.ifEmpty { listOf(String.format("%02d:%02d", rule.hour, rule.minute)) }.joinToString("、")
        val cycleText = TYPE_LABELS[rule.type] ?: rule.type.name
        Text("$cycleText · $timeText", style = MaterialTheme.typography.bodyMedium)
        rule.effectiveFireAt?.let {
            val next = RuleEngine.fromEpochMillis(it)
            Text(
                "下次：${RuleEngine.formatDate(next.toLocalDate())} ${String.format("%02d:%02d", next.hour, next.minute)}",
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
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixSwitch(
                checked = rule.status == RuleStatus.ENABLED,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(end = 8.dp)
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
