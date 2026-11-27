package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.util.t

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onOpenLogs: (() -> Unit)? = null) {
    val context = LocalContext.current
    val themeMode by vm.themeMode.collectAsState()
    val showExec by vm.showExecutionStatusFlow.collectAsState()
    val runTrack by vm.runTrackingFlow.collectAsState()
    val haptics by vm.hapticsFlow.collectAsState()
    val pm = context.getSystemService(PowerManager::class.java)
    val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        pm?.isIgnoringBatteryOptimizations(context.packageName) != true
    } else false

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                title = { Text(t("设置")) },
                actions = {
                    IconButton(onClick = { com.lucaslu.tickhub.util.Haptics.tick(context); vm.cycleTheme() }) {
                        Text(
                            when (themeMode) {
                                0 -> "☀"   // light
                                1 -> "☾"   // dark
                                else -> "◐" // system
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 88.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            fun setShowExecution(enabled: Boolean) { vm.setShowExecutionStatus(enabled) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(t("后台运行"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (batteryOptimized) t("⚠ 电池优化开启，定时可能延迟") else t("✓ 已加入电池白名单"),
                        color = if (batteryOptimized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(t("国产 ROM 还需开启「自启动」权限，否则划掉后台后定时任务不会执行。"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(
                            onClick = { com.lucaslu.tickhub.util.Haptics.tick(context); openBatterySettings(context) },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        ) { Text(t("电池无限制"), maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                        OutlinedButton(
                            onClick = { com.lucaslu.tickhub.util.Haptics.tick(context); openAppDetails(context) },
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        ) { Text(t("自启动设置"), maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
                val canExact = alarmManager?.canScheduleExactAlarms() == true
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(t("精确闹钟"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (canExact) t("✓ 已允许精确闹钟，触发更准时") else t("⚠ 未允许精确闹钟，触发可能延迟数分钟"),
                            color = if (canExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!canExact) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { com.lucaslu.tickhub.util.Haptics.tick(context); openExactAlarmSettings(context) }) { Text(t("去开启精确闹钟")) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t("执行状态监测"), style = MaterialTheme.typography.titleMedium)
                            Text(t("触发时显示进度与用时，仅前台刷新"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MiuixSwitch(checked = showExec, onCheckedChange = { setShowExecution(it) }, description = t("执行状态监测"))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t("执行结果通知"), style = MaterialTheme.typography.titleMedium)
                            Text(t("任务完成后通知成功或失败，并显示真实用时"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MiuixSwitch(checked = runTrack, onCheckedChange = { vm.setRunTrackingEnabled(it) }, description = t("执行结果通知"))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t("触感反馈"), style = MaterialTheme.typography.titleMedium)
                            Text(t("开关、滑动、拨轮、页面切换的短震动反馈"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MiuixSwitch(checked = haptics, onCheckedChange = { vm.setHapticsEnabled(it) }, description = t("触感反馈"))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // China-only statutory holiday calendar — opt-in, default off
            val holidayEnabled by vm.holidayEnabledFlow.collectAsState()
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t("中国法定节假日"), style = MaterialTheme.typography.titleMedium)
                            Text(t("仅中国用户需要：开启后拉取节假日数据，支持「法定工作日/节假日」周期。非中国地区不支持此周期。"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MiuixSwitch(checked = holidayEnabled, onCheckedChange = { vm.setHolidayEnabled(it) }, description = t("中国法定节假日"))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(t("网络恢复机制"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("到点唤醒手机，无网络时最多等待 3 分钟，联网后立即触发，避免夜间断网漏签。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth().clickable { com.lucaslu.tickhub.util.Haptics.tick(context); onOpenLogs?.invoke() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(t("调试日志"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(t("记录运行过程，可导出排查问题"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))

            // language switch — last card, above sign out
            val curLang by com.lucaslu.tickhub.util.L10n.lang.collectAsState()
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(t("语言"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = {
                            vm.setLanguage(com.lucaslu.tickhub.util.L10n.Lang.ZH)
                            com.lucaslu.tickhub.util.Haptics.tick(context)
                        }, enabled = curLang != com.lucaslu.tickhub.util.L10n.Lang.ZH) { Text("中文") }
                        Spacer(Modifier.padding(8.dp))
                        Button(onClick = {
                            vm.setLanguage(com.lucaslu.tickhub.util.L10n.Lang.EN)
                            com.lucaslu.tickhub.util.Haptics.tick(context)
                        }, enabled = curLang != com.lucaslu.tickhub.util.L10n.Lang.EN) { Text("English") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = { com.lucaslu.tickhub.util.Haptics.tick(context); vm.logout() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(t("退出登录"), color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun openBatterySettings(context: Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openExactAlarmSettings(context: Context) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
