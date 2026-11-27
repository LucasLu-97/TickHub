package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.util.t

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.data.RuleType
import com.lucaslu.tickhub.data.WorkflowItem
import com.lucaslu.tickhub.domain.RuleEngine
import com.lucaslu.tickhub.util.Haptics
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Suppress("DEPRECATION")
private val TYPE_LABELS = mapOf(
    RuleType.ONCE to "单次", RuleType.DAILY to "每天", RuleType.WEEKLY to "每周",
    RuleType.LEGAL_WORKDAY to "法定工作日", RuleType.LEGAL_HOLIDAY to "法定节假日",
    RuleType.EVERY_N_DAYS to "每隔X天", RuleType.WEEKDAYS to "法定工作日"
)
private val WEEKDAY_LABELS = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")

@Composable
fun RuleEditScreen(
    workflows: List<WorkflowItem>,
    repoNames: Map<Long, String>,
    existing: Rule?,
    onSave: (List<Rule>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var selectedIds by remember {
        mutableStateOf(existing?.resolvedWorkflowIds()?.toSet() ?: emptySet())
    }
    var type by remember { mutableStateOf(existing?.type ?: RuleType.DAILY) }
    var timeSlots by remember {
        mutableStateOf(
            existing?.timeSlots?.ifEmpty { listOf("%02d:%02d".format(existing.hour, existing.minute)) }
                ?: listOf("08:00")
        )
    }
    var onceDate by remember { mutableStateOf(existing?.onceDate ?: RuleEngine.formatDate(LocalDate.now().plusDays(1))) }
    var selectedDays by remember { mutableStateOf(existing?.weekdays ?: listOf(1)) }
    var interval by remember { mutableStateOf(existing?.intervalDays ?: 7) }
    var startDate by remember { mutableStateOf(existing?.startDate ?: RuleEngine.formatDate(LocalDate.now())) }
    var driftMinutes by remember { mutableStateOf(existing?.driftMinutes ?: 0) }
    var error by remember { mutableStateOf<String?>(null) }

    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showInterval by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf("once") }

    // editing shows only the repo this rule belongs to; creating shows all
    val editRepoId = existing?.repoId ?: 0L
    val visibleWorkflows = if (existing != null) workflows.filter { it.repoId == editRepoId } else workflows
    val grouped = visibleWorkflows.groupBy { it.repoId }
    var expandedRepo by remember {
        mutableStateOf<Long?>(if (existing != null) editRepoId else null)
    }
    fun repoLabel(id: Long) = (repoNames[id] ?: "${com.lucaslu.tickhub.util.L10n.tNow("仓库 ")}$id").split("/").lastOrNull() ?: "${com.lucaslu.tickhub.util.L10n.tNow("仓库 ")}$id"

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).padding(top = 40.dp, bottom = 120.dp)) {
        Text(if (existing == null) t("新建触发规则") else t("编辑触发规则"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Text(t("① 选择仓库（点开后勾选工作流，可多选）"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (grouped.isEmpty()) {
            Text(t("还没有纳管的工作流。请先在「仓库」页勾选工作流。"), color = MaterialTheme.colorScheme.error)
        }
        grouped.forEach { (repoId, wfs) ->
            TextButton(onClick = {
                expandedRepo = if (expandedRepo == repoId) null else repoId
                Haptics.tick(context)
            }) {
                Text("${repoLabel(repoId)}  (${wfs.size})  ${if (expandedRepo == repoId) "▾" else "▸"}")
            }
            if (expandedRepo == repoId) {
                wfs.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        row.forEach { wf ->
                            FilterChip(
                                selected = selectedIds.contains(wf.id),
                                onClick = {
                                    selectedIds = if (selectedIds.contains(wf.id)) selectedIds - wf.id else selectedIds + wf.id
                                    Haptics.tick(context)
                                },
                                label = { Text(wf.name, maxLines = 1) },
                                modifier = Modifier.weight(1f).padding(end = 4.dp)
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(t("② 触发时间（可添加多个时段）"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        timeSlots.forEachIndexed { idx, slot ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(t("时间") + "${idx + 1}：$slot", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { editingSlotIndex = idx; Haptics.tick(context) }) { Text(t("修改")) }
                if (timeSlots.size > 1) {
                    TextButton(onClick = { Haptics.tick(context); timeSlots = timeSlots.filterIndexed { i, _ -> i != idx } }) { Text(t("移除")) }
                }
            }
        }
        TextButton(onClick = {
            val last = timeSlots.lastOrNull() ?: "08:00"
            val h = (last.split(":").getOrNull(0)?.toIntOrNull() ?: 8) + 1
            timeSlots = timeSlots + "${(h % 24).toString().padStart(2, '0')}:00"
            Haptics.tick(context)
        }) { Text(t("＋ 添加时间段")) }
        Spacer(Modifier.height(16.dp))

        Text(t("③ 重复周期"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        RuleType.entries.filter { it != RuleType.WEEKDAYS }.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t; Haptics.tick(context) },
                        label = { Text(t(TYPE_LABELS[t] ?: t.name)) },
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(4.dp))
        }
        when (type) {
            RuleType.ONCE -> TextButton(onClick = { Haptics.tick(context); dateTarget = "once"; showDate = true }) { Text(t("日期：") + "$onceDate  " + t("修改")) }
            RuleType.WEEKLY -> {
                Text(t("选择星期（可多选）"), style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.fillMaxWidth()) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = selectedDays.contains(d),
                            onClick = {
                                selectedDays = if (selectedDays.contains(d)) selectedDays - d else selectedDays + d
                                Haptics.tick(context)
                            },
                            label = { Text(t(WEEKDAY_LABELS[d] ?: d.toString())) },
                            modifier = Modifier.weight(1f).padding(end = 2.dp)
                        )
                    }
                }
            }
            RuleType.EVERY_N_DAYS -> {
                TextButton(onClick = { Haptics.tick(context); showInterval = true }) { Text(t("每隔 ") + "$interval" + t(" 天（1=每天）") + "  " + t("修改")) }
                TextButton(onClick = { Haptics.tick(context); dateTarget = "start"; showDate = true }) { Text(t("起始日期：") + "$startDate  " + t("修改")) }
            }
            else -> Unit
        }
        Spacer(Modifier.height(16.dp))

        Text(t("④ 随机漂移（规避签到规律检测）"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(0, 10, 30, 60).forEach { d ->
                FilterChip(
                    selected = driftMinutes == d,
                    onClick = { driftMinutes = d; Haptics.tick(context) },
                    label = { Text(if (d == 0) t("不漂移") else "$d" + t("分")) },
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                Haptics.tick(context)
                when {
                    selectedIds.isEmpty() -> error = com.lucaslu.tickhub.util.L10n.tNow("请至少勾选一个工作流")
                    type == RuleType.WEEKLY && selectedDays.isEmpty() -> error = com.lucaslu.tickhub.util.L10n.tNow("请至少选择一个星期")
                    else -> {
                        val chosen = workflows.filter { selectedIds.contains(it.id) }
                        // split by repo: one rule per repo, each with its own workflows
                        val rules = chosen.groupBy { it.repoId }.map { (repoId, wfs) ->
                            Rule(
                                id = UUID.randomUUID().toString(),
                                workflowId = wfs.first().id,
                                workflowIds = wfs.map { it.id },
                                workflowName = wfs.first().name,
                                workflowNames = wfs.map { it.name },
                                repoFullName = repoNames[repoId] ?: "",
                                repoId = repoId,
                                type = type,
                                hour = parseHour(timeSlots.first()),
                                minute = parseMinute(timeSlots.first()),
                                timeSlots = timeSlots,
                                onceDate = if (type == RuleType.ONCE) onceDate else null,
                                weekdays = if (type == RuleType.WEEKLY) selectedDays.sorted() else emptyList(),
                                intervalDays = if (type == RuleType.EVERY_N_DAYS) interval else 7,
                                startDate = if (type == RuleType.EVERY_N_DAYS) startDate else null,
                                driftMinutes = driftMinutes,
                                status = RuleStatus.ENABLED,
                                createdAt = System.currentTimeMillis()
                            )
                        }
                        onSave(rules)
                    }
                }
            }, modifier = Modifier.weight(1f)) { Text(t("保存")) }
            Spacer(Modifier.padding(8.dp))
            OutlinedButton(onClick = { Haptics.tick(context); onCancel() }, modifier = Modifier.weight(1f)) { Text(t("取消")) }
        }
    }

    editingSlotIndex?.let { idx ->
        TimePickerDialog(
            initial = timeSlots[idx],
            onConfirm = { v ->
                timeSlots = timeSlots.mapIndexed { i, s -> if (i == idx) v else s }
                editingSlotIndex = null
            },
            onDismiss = { editingSlotIndex = null }
        )
    }
    if (showInterval) IntervalDialog(interval, { interval = it; showInterval = false }, { showInterval = false })
    if (showDate) {
        DatePickerDialog(if (dateTarget == "once") onceDate else startDate, { v ->
            if (dateTarget == "once") onceDate = v else startDate = v
            showDate = false
        }, { showDate = false })
    }
}

private fun parseHour(slot: String) = slot.split(":").getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
private fun parseMinute(slot: String) = slot.split(":").getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
