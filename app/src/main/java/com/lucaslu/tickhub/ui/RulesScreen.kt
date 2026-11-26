package com.lucaslu.tickhub.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.scrollBy
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    val groups = remember(orderedRules, repoNameOrder) {
        val g = linkedMapOf<String, List<com.lucaslu.tickhub.data.Rule>>()
        val raw = orderedRules.groupBy { groupNameOf(it) }
        repoNameOrder.forEach { raw[it]?.let { v -> g[it] = v } }
        raw.forEach { (k, v) -> if (!g.containsKey(k)) g[k] = v }
        g
    }

    // Single flat LazyColumn for the whole page (all groups tiled). While dragging,
    // cards of OTHER groups are marked enabled=false on their ReorderableItem, so
    // the library itself refuses cross-group swaps and stops edge-scroll at the
    // group's first/last row — no manual page scrolling, no card drop.
    var activeGroup by remember { mutableStateOf<String?>(null) }
    var draggingRuleId by remember { mutableStateOf<String?>(null) }
    var dragFingerY by remember { mutableFloatStateOf(-1f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // slots mirror the LazyColumn: "h:<group>" for headers, rule id for cards
    val slots = remember(groups) {
        buildList {
            groups.forEach { (name, rs) ->
                add("h:$name")
                rs.forEach { add(it.id) }
            }
        }
    }
    // Library scroller only stops at the whole-list edge, not the group edge, so we
    // disable it (scrollThreshold=0) and drive edge scrolling ourselves. The library's
    // draggingItemOffset compensates list scroll automatically, so the lifted card
    // stays glued to the finger while we animateScroll the list.
    val reorderState = sh.calvin.reorderable.rememberReorderableLazyListState(
        lazyListState,
        scrollThreshold = 0.dp
    ) { from, to ->
        val fromKey = slots.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        val toKey = slots.getOrNull(to.index) ?: return@rememberReorderableLazyListState
        if (fromKey.startsWith("h:") || toKey.startsWith("h:")) return@rememberReorderableLazyListState
        val fi = orderedRules.indexOfFirst { it.id == fromKey }
        val ti = orderedRules.indexOfFirst { it.id == toKey }
        if (fi < 0 || ti < 0) return@rememberReorderableLazyListState
        orderedRules = orderedRules.toMutableList().apply { add(ti, removeAt(fi)) }
        vm.saveOrder("rules", orderedRules.map { it.id })
    }

    // Edge auto-scroll limited to the dragged card's own group: scroll the list,
    // and once the card passes a sibling, swap them ("哒哒哒"). Stop exactly at
    // this group's first/last row even if the finger stays at the screen edge.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edgePx = with(density) { 96.dp.toPx() }
    val rowPx = with(density) { 104.dp.toPx() }
    LaunchedEffect(draggingRuleId) {
        val dragId = draggingRuleId ?: return@LaunchedEffect
        var scrollAccum = 0f
        while (true) {
            kotlinx.coroutines.delay(16)
            val rule = orderedRules.firstOrNull { it.id == dragId } ?: break
            val group = groupNameOf(rule)
            val groupIds = groups[group]?.map { it.id } ?: break
            val gi = groupIds.indexOf(dragId)
            val y = dragFingerY
            val h = viewportHeight.toFloat()
            val speed = when {
                y >= 0f && y < edgePx && gi > 0 -> -rowPx * 0.28f   // near top, can go up
                y > h - edgePx && gi < groupIds.lastIndex -> rowPx * 0.28f // near bottom, can go down
                else -> 0f
            }
            if (speed == 0f) { scrollAccum = 0f; continue }
            // scroll the list a step; library compensates card position
            lazyListState.scrollBy(speed)
            scrollAccum += kotlin.math.abs(speed)
            if (scrollAccum >= rowPx * 0.55f) {
                scrollAccum = 0f
                // swap dragged card with the sibling in the scroll direction
                val swapWith = if (speed < 0f) gi - 1 else gi + 1
                if (swapWith in groupIds.indices) {
                    val otherId = groupIds[swapWith]
                    val fi = orderedRules.indexOfFirst { it.id == dragId }
                    val ti = orderedRules.indexOfFirst { it.id == otherId }
                    if (fi >= 0 && ti >= 0) {
                        orderedRules = orderedRules.toMutableList().apply { add(ti, removeAt(fi)) }
                        vm.saveOrder("rules", orderedRules.map { it.id })
                    }
                }
            }
        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
        if (selectedWorkflows.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("请先在「仓库」页勾选要纳管的工作流，再来创建规则。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewportHeight = it.height }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            dragFingerY = event.changes.firstOrNull()?.position?.y ?: -1f
                            if (event.changes.all { !it.pressed }) break
                        }
                    }
                },
            state = lazyListState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
        ) {
            items(slots, key = { it }) { key ->
                if (key.startsWith("h:")) {
                    Text(
                        key.removePrefix("h:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                } else {
                    val rule = orderedRules.firstOrNull { it.id == key }
                    if (rule != null) {
                        val group = groupNameOf(rule)
                        val canDrag = (groups[group]?.size ?: 0) > 1
                        // during a drag, only cards of the active group stay reorderable
                        val enabled = activeGroup == null || activeGroup == group
                        ReorderableItem(reorderState, key = rule.id, enabled = enabled) { isDragging ->
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
                                autoSelected = multiSelect && selectedIds.contains(rule.id),
                                dragging = isDragging,
                                dragHandle = if (canDrag) with(this) {
                                    Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            activeGroup = group
                                            draggingRuleId = rule.id
                                            dragFingerY = -1f
                                            Haptics.tick(context)
                                        },
                                        onDragStopped = {
                                            activeGroup = null
                                            draggingRuleId = null
                                            dragFingerY = -1f
                                            Haptics.tick(context)
                                        }
                                    )
                                } else Modifier,
                                content = {
                                    RuleCardContent(
                                        rule = rule,
                                        onToggle = { Haptics.tick(context); vm.setRuleEnabled(rule.id, it) },
                                        onEdit = { Haptics.tick(context); editing = rule },
                                        onTriggerNow = { Haptics.tick(context); confirmTrigger = rule },
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
