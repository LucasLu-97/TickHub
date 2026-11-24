package com.lucaslu.tickhub.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleType
import com.lucaslu.tickhub.data.WorkflowItem
import com.lucaslu.tickhub.domain.RuleEngine
import java.time.LocalDate
import java.time.LocalDateTime
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
    var hourText by remember { mutableStateOf((existing?.hour ?: 8).toString()) }
    var minuteText by remember { mutableStateOf((existing?.minute ?: 0).toString().padStart(2, '0')) }
    var onceDate by remember { mutableStateOf(existing?.onceDate ?: RuleEngine.formatDate(LocalDate.now().plusDays(1))) }
    var selectedDays by remember { mutableStateOf(existing?.weekdays ?: listOf(1)) }
    var intervalText by remember { mutableStateOf((existing?.intervalDays ?: 7).toString()) }
    var startDate by remember { mutableStateOf(existing?.startDate ?: RuleEngine.formatDate(LocalDate.now())) }
    var error by remember { mutableStateOf<String?>(null) }

    val hour = hourText.toIntOrNull()
    val minute = minuteText.toIntOrNull()
    val interval = intervalText.toIntOrNull()

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("新建触发规则", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Text("选择工作流", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (workflows.isEmpty()) {
            Text("还没有纳管的工作流。请先在「仓库」页勾选工作流。", color = MaterialTheme.colorScheme.error)
        } else {
            workflows.forEach { wf ->
                FilterChip(
                    selected = selectedWorkflowId == wf.id,
                    onClick = { selectedWorkflowId = wf.id },
                    label = { Text(wf.name) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Text("触发时间（24 小时制）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = hourText,
            onValueChange = { hourText = it },
            label = { Text("时 (0-23)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = minuteText,
            onValueChange = { minuteText = it },
            label = { Text("分 (0-59)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        Text("重复周期", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Column {
            RuleType.entries.forEach { t ->
                FilterChip(
                    selected = type == t,
                    onClick = { type = t },
                    label = { Text(TYPE_LABELS[t] ?: t.name) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (type) {
            RuleType.ONCE -> {
                OutlinedTextField(value = onceDate, onValueChange = { onceDate = it }, label = { Text("日期 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())
            }
            RuleType.WEEKLY -> {
                Text("选择星期", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Column {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = selectedDays.contains(d),
                            onClick = {
                                selectedDays = if (selectedDays.contains(d)) selectedDays - d else selectedDays + d
                            },
                            label = { Text(WEEKDAY_LABELS[d] ?: d.toString()) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
            RuleType.EVERY_N_DAYS -> {
                OutlinedTextField(value = intervalText, onValueChange = { intervalText = it }, label = { Text("间隔天数 N") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = startDate, onValueChange = { startDate = it }, label = { Text("起始日期 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())
            }
            else -> Unit
        }
        Spacer(Modifier.height(24.dp))

        // Preview next fire time
        val previewRule = Rule(
            id = existing?.id ?: "preview",
            workflowId = selectedWorkflowId,
            type = type,
            hour = hour ?: 0,
            minute = minute ?: 0,
            onceDate = onceDate,
            weekdays = selectedDays,
            intervalDays = interval ?: 1,
            startDate = startDate
        )
        val next = RuleEngine.computeNextFire(previewRule, LocalDateTime.now())
        Text(
            "下次触发：" + (next?.let { RuleEngine.formatDate(it.toLocalDate()) + " " + String.format("%02d:%02d", it.hour, it.minute) } ?: "无法计算，请检查参数"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Column {
            Button(onClick = {
                when {
                    workflows.isEmpty() -> error = "请先纳管工作流"
                    hour == null || hour !in 0..23 -> error = "小时必须是 0-23"
                    minute == null || minute !in 0..59 -> error = "分钟必须是 0-59"
                    type == RuleType.WEEKLY && selectedDays.isEmpty() -> error = "请至少选择一个星期"
                    type == RuleType.EVERY_N_DAYS && (interval == null || interval < 1) -> error = "间隔天数必须 ≥1"
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
                                hour = hour ?: 0,
                                minute = minute ?: 0,
                                onceDate = if (type == RuleType.ONCE) onceDate else null,
                                weekdays = if (type == RuleType.WEEKLY) selectedDays.sorted() else emptyList(),
                                intervalDays = if (type == RuleType.EVERY_N_DAYS) (interval ?: 7) else 7,
                                startDate = if (type == RuleType.EVERY_N_DAYS) startDate else null,
                                status = existing?.status ?: com.lucaslu.tickhub.data.RuleStatus.ENABLED,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis()
                            )
                        )
                    }
                }
            }) { Text("保存") }
            Spacer(Modifier.padding(8.dp))
            Button(onClick = onCancel) { Text("取消") }
        }
    }
}
