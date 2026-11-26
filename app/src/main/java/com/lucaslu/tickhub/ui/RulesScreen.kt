package com.lucaslu.tickhub.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
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
import com.lucaslu.tickhub.util.Haptics
import sh.calvin.reorderable.ReorderableItem

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
    val context = androidx.compose.ui.platform.LocalContext.current
    val rules by vm.rules.collectAsState(initial = emptyList())
    val snapshot by vm.snapshot.collectAsState(initial = com.lucaslu.tickhub.data.ScanSnapshot())
    val running by vm.runningExecutions.collectAsState(initial = emptyMap())
    val showExec by vm.showExecutionStatusFlow.collectAsState()

    var editing by remember { mutableStateOf<Rule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var confirmTrigger by remember { mutableStateOf<Rule?>(null) }
    val orders by vm.orders.collectAsState()
    var orderedRules by remember { mutableStateOf<List<com.lucaslu.tickhub.data.Rule>>(emptyList()) }
    val savedRuleOrder = orders["rules"] ?: emptyList()
    val savedRepoOrder = orders["repos"] ?: emptyList()
    val repoNameOrder = vm.applyOrder(snapshot.repos, savedRepoOrder) { it.id.toString() }
        .map { it.fullName.split("/").lastOrNull() ?: it.fullName }

    fun groupNameOf(rule: com.lucaslu.tickhub.data.Rule): String =
        rule.repoFullName.split("/").lastOrNull().takeUnless { it.isNullOrBlank() } ?: "未分类"

    LaunchedEffect(rules, savedRuleOrder) {
        orderedRules = vm.applyOrder(rules, savedRuleOrder) { it.id }
    }

    // Group rules by repo, ordered by the repo page's saved order.
    // Each group gets its OWN LazyColumn + Reorderable state, so a card physically
    // cannot leave its group — no cross-group ghost animation at all.
    val groups = remember(orderedRules, repoNameOrder) {
        val g = linkedMapOf<String, List<com.lucaslu.tickhub.data.Rule>>()
        val raw = orderedRules.groupBy { groupNameOf(it) }
        repoNameOrder.forEach { raw[it]?.let { v -> g[it] = v } }
        raw.forEach { (k, v) -> if (!g.containsKey(k)) g[k] = v }
        g
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
                TextButton(onClick = { Haptics.tick(context); vm.triggerNow(ruleToTrigger); confirmTrigger = null }) { Text("确认触发") }
            },
            dismissButton = { TextButton(onClick = { Haptics.tick(context); confirmTrigger = null }) { Text("取消") } }
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
                            Haptics.tick(context)
                            selectedIds = if (selectedIds.size == rules.size) emptySet() else rules.map { it.id }.toSet()
                        }) { Text("全选") }
                        TextButton(onClick = {
                            Haptics.tick(context)
                            selectedIds.forEach { vm.deleteRule(it) }
                            selectedIds = emptySet(); multiSelect = false
                        }) { Text("删除") }
                        TextButton(onClick = { Haptics.tick(context); multiSelect = false; selectedIds = emptySet() }) { Text("取消") }
                    } else {
                        androidx.compose.material3.IconButton(onClick = { Haptics.tick(context); creating = true }, enabled = selectedWorkflows.isNotEmpty()) {
                            Text("＋", style = MaterialTheme.typography.titleLarge)
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(bottom = 88.dp)
        ) {
            groups.forEach { (repoName, groupRules) ->
                Text(
                    repoName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                RuleGroupList(
                    rules = groupRules,
                    context = context,
                    multiSelect = multiSelect,
                    selectedIds = selectedIds,
                    onSelect = { id, sel ->
                        multiSelect = true
                        selectedIds = if (sel) selectedIds + id else selectedIds - id
                    },
                    onClickRule = { id ->
                        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
                    },
                    onDelete = { vm.deleteRule(it) },
                    onToggle = { vm.setRuleEnabled(it.first, it.second) },
                    onEdit = { editing = it },
                    onTriggerNow = { confirmTrigger = it },
                    showExec = showExec,
                    running = running,
                    onReorder = { newGroupRules ->
                        // splice this group's new order back into the global orderedRules
                        val idSet = groupRules.map { it.id }.toSet()
                        var cursor = 0
                        orderedRules = orderedRules.map { r ->
                            if (r.id in idSet) newGroupRules.getOrNull(cursor++) ?: r else r
                        }
                        vm.saveOrder("rules", orderedRules.map { it.id })
                    }
                )
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

/**
 * One repo group with its own reorder state. Because each group is a separate
 * LazyColumn, cards can never be dragged outside the group boundary.
 */
@Composable
private fun RuleGroupList(
    rules: List<com.lucaslu.tickhub.data.Rule>,
    context: android.content.Context,
    multiSelect: Boolean,
    selectedIds: Set<String>,
    onSelect: (String, Boolean) -> Unit,
    onClickRule: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (Pair<String, Boolean>) -> Unit,
    onEdit: (com.lucaslu.tickhub.data.Rule) -> Unit,
    onTriggerNow: (com.lucaslu.tickhub.data.Rule) -> Unit,
    showExec: Boolean,
    running: Map<String, Long>,
    onReorder: (List<com.lucaslu.tickhub.data.Rule>) -> Unit
) {
    var groupRules by remember(rules) { mutableStateOf(rules) }
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorderState = sh.calvin.reorderable.rememberReorderableLazyListState(lazyListState) { from, to ->
        groupRules = groupRules.toMutableList().apply { add(to.index, removeAt(from.index)) }
        onReorder(groupRules)
    }
    val canDrag = groupRules.size > 1
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height((groupRules.size * 112).dp),
        state = lazyListState,
        userScrollEnabled = false // group list is short; outer column handles scrolling
    ) {
        items(groupRules, key = { it.id }) { rule ->
            ReorderableItem(reorderState, key = rule.id) { isDragging ->
                SwipeableCard(
                    modifier = Modifier.fillMaxWidth(),
                    onDelete = { onDelete(rule.id) },
                    onClick = { if (multiSelect) onClickRule(rule.id) },
                    onSelect = { sel -> onSelect(rule.id, sel) },
                    selected = selectedIds.contains(rule.id),
                    autoSelected = multiSelect && selectedIds.contains(rule.id),
                    dragging = isDragging,
                    dragHandle = if (canDrag) with(this) {
                        Modifier.longPressDraggableHandle(
                            onDragStarted = { Haptics.tick(context) },
                            onDragStopped = { Haptics.tick(context) }
                        )
                    } else Modifier,
                    content = {
                        RuleCardContent(
                            rule = rule,
                            onToggle = { Haptics.tick(context); onToggle(rule.id to it) },
                            onEdit = { Haptics.tick(context); onEdit(rule) },
                            onTriggerNow = { Haptics.tick(context); onTriggerNow(rule) },
                            runningStart = if (showExec) running[rule.id] else null
                        )
                    }
                )
            }
        }
    }
}
