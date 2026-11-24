package com.lucaslu.tickhub.ui

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
import androidx.compose.runtime.Composable
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID

private val TYPE_LABELS = mapOf(
    RuleType.ONCE to "一次",
    RuleType.DAILY to "每天",
    RuleType.WEEKDAYS to "每工作日",
    RuleType.WEEKLY to "每周",
    RuleType.EVERY_N_DAYS to "每N天"
)

private val WEEKDAY_LABELS = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")

@Composable
fun RuleEditScreen(
    workflows: List<WorkflowItem>,
    repoNames: Map<Long, String>,
    existing: Rule?,
    onSave: (Rule) -> Unit,
    onCancel: () -> Unit
) {
    var selectedWorkflowId by remember { mutableStateOf(existing?.workflowId ?: workflows.firstOrNull()?.id ?: 0L) }
    var type by remember { mutableStateOf(existing?.type ?: RuleType.DAILY) }
    var hour by remember { mutableStateOf(existing?.hour ?: 8) }
    var minute by remember { mutableStateOf(existing?.minute ?: 0) }
    var onceDate by remember { mutableStateOf(existing?.onceDate ?: RuleEngine.formatDate(LocalDate.now().plusDays(1))) }
    var selectedDays by remember { mutableStateOf(existing?.weekdays ?: listOf(1)) }
    var interval by remember { mutableStateOf(existing?.intervalDays ?: 7) }
    var startDate by remember { mutableStateOf(existing?.startDate ?: RuleEngine.formatDate(LocalDate.now())) }
    var error by remember { mutableStateOf<String?>(null) }

    val hourItems = (0..23).map { it.toString().padStart(2, '0') }
    val minuteItems = (0..59).map { it.toString().padStart(2, '0') }
    val intervalItems = (1..30).map { it.toString() }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(if (existing == null) "新建触发规则" else "编辑触发规则", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // ---- workflow selection grouped by repo ----
        Text("选择工作流", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (workflows.isEmpty()) {
            Text("还没有纳管的工作流。请先在「仓库」页勾选工作流。", color = MaterialTheme.colorScheme.error)
        } else {
            val grouped = workflows.groupBy { it.repoId }
            grouped.forEach { (repoId, wfs) ->
                Text(
                    repoNames[repoId] ?: "仓库 $repoId",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                // horizontal wrapping via rows of chips
                val rows = wfs.chunked(3)
                rows.forEach { rowWfs ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowWfs.forEach { wf ->
                            FilterChip(
                                selected = selectedWorkflowId == wf.id,
                                onClick = { selectedWorkflowId = wf.id },
                                label = { Text(wf.name, maxLines = 1) },
                                modifier = Modifier.weight(1f).padding(end = 4.dp)
                            )
                        }
                        repeat(3 - rowWfs.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- time wheel ----
        Text("触发时间（24 小时制）", style = MaterialTheme.typography.titleSmall)
        Text("上下拨动选择，松手后自动减速定位", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WheelPicker(
                items = hourItems,
                selectedIndex = hour,
                onSelectedChange = { hour = it },
                modifier = Modifier.weight(1f)
            )
            Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
            WheelPicker(
                items = minuteItems,
                selectedIndex = minute,
                onSelectedChange = { minute = it },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))

        // ---- cycle type: horizontal wrapping ----
        Text("重复周期", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val types = RuleType.entries
        types.chunked(3).forEach { rowTypes ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowTypes.forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        label = { Text(TYPE_LABELS[t] ?: t.name) },
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )
                }
                repeat(3 - rowTypes.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(12.dp))

        when (type) {
            RuleType.ONCE -> {
                Text("选择日期", style = MaterialTheme.typography.labelLarge)
                DateWheelPicker(date = onceDate, onDateChange = { onceDate = it })
            }
            RuleType.WEEKLY -> {
                Text("选择星期（可多选）", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = selectedDays.contains(d),
                            onClick = {
                                selectedDays = if (selectedDays.contains(d)) selectedDays - d else selectedDays + d
                            },
                            label = { Text(WEEKDAY_LABELS[d] ?: d.toString()) },
                            modifier = Modifier.weight(1f).padding(end = 2.dp)
                        )
                    }
                }
            }
            RuleType.EVERY_N_DAYS -> {
                Text("间隔天数：1 表示每天，1–30 循环选择", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("每", style = MaterialTheme.typography.bodyLarge)
                    WheelPicker(
                        items = intervalItems,
                        selectedIndex = (interval - 1).coerceIn(0, 29),
                        onSelectedChange = { interval = it + 1 },
                        modifier = Modifier.weight(1f)
                    )
                    Text("天", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                Text("起始日期", style = MaterialTheme.typography.labelLarge)
                DateWheelPicker(date = startDate, onDateChange = { startDate = it })
            }
            else -> Unit
        }
        Spacer(Modifier.height(24.dp))

        // preview
        val previewRule = Rule(
            id = existing?.id ?: "preview",
            workflowId = selectedWorkflowId,
            type = type,
            hour = hour,
            minute = minute,
            onceDate = onceDate,
            weekdays = selectedDays,
            intervalDays = interval,
            startDate = startDate
        )
        val next = RuleEngine.computeNextFire(previewRule, LocalDateTime.now())
        Text(
            "下次触发：" + (next?.let {
                RuleEngine.formatDate(it.toLocalDate()) + " " + String.format("%02d:%02d", it.hour, it.minute)
            } ?: "无法计算，请检查参数"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                when {
                    workflows.isEmpty() -> error = "请先纳管工作流"
                    type == RuleType.WEEKLY && selectedDays.isEmpty() -> error = "请至少选择一个星期"
                    next == null -> error = "无法计算下次触发时间，请检查日期/参数"
                    else -> {
                        val wf = workflows.first { it.id == selectedWorkflowId }
                        onSave(
                            Rule(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                workflowId = wf.id,
                                workflowName = wf.name,
                                repoFullName = repoNames[wf.repoId] ?: "",
                                type = type,
                                hour = hour,
                                minute = minute,
                                onceDate = if (type == RuleType.ONCE) onceDate else null,
                                weekdays = if (type == RuleType.WEEKLY) selectedDays.sorted() else emptyList(),
                                intervalDays = if (type == RuleType.EVERY_N_DAYS) interval else 7,
                                startDate = if (type == RuleType.EVERY_N_DAYS) startDate else null,
                                status = existing?.status ?: RuleStatus.ENABLED,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis()
                            )
                        )
                    }
                }
            }, modifier = Modifier.weight(1f)) { Text("保存") }
            Spacer(Modifier.padding(8.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
        }
    }
}

@Composable
private fun DateWheelPicker(date: String, onDateChange: (String) -> Unit) {
    val today = LocalDate.now()
    val parsed = RuleEngine.parseDate(date) ?: today
    val years = (today.year..(today.year + 5)).map { it.toString() }
    val months = (1..12).map { it.toString() }
    val maxDay = YearMonth.of(parsed.year, parsed.monthValue).lengthOfMonth()
    val days = (1..maxDay).map { it.toString() }

    var yearIdx by remember { mutableStateOf((parsed.year - today.year).coerceIn(0, years.lastIndex)) }
    var monthIdx by remember { mutableStateOf((parsed.monthValue - 1).coerceIn(0, 11)) }
    var dayIdx by remember { mutableStateOf((parsed.dayOfMonth - 1).coerceIn(0, maxDay - 1)) }

    fun emit() {
        val y = today.year + yearIdx
        val m = monthIdx + 1
        val maxD = YearMonth.of(y, m).lengthOfMonth()
        val d = (dayIdx + 1).coerceAtMost(maxD)
        onDateChange("$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}")
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WheelPicker(items = years, selectedIndex = yearIdx, onSelectedChange = { yearIdx = it; emit() }, modifier = Modifier.weight(1.2f))
        WheelPicker(items = months, selectedIndex = monthIdx, onSelectedChange = { monthIdx = it; emit() }, modifier = Modifier.weight(0.8f))
        WheelPicker(items = days, selectedIndex = dayIdx, onSelectedChange = { dayIdx = it; emit() }, modifier = Modifier.weight(0.8f))
    }
}
