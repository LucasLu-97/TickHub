package com.lucaslu.tickhub.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val waitMinutes by vm.networkWaitMinutes.collectAsState(initial = 5)
    val pm = context.getSystemService(PowerManager::class.java)
    val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        pm?.isIgnoringBatteryOptimizations(context.packageName) != true
    } else false

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("设置与权限", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("后台运行", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (batteryOptimized) "⚠ 电池优化开启，定时可能延迟" else "✓ 已加入电池白名单",
                    color = if (batteryOptimized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text("国产 ROM 还需开启「自启动」权限，否则划掉后台后定时任务不会执行。不同品牌路径不同，请到系统设置中搜索「自启动」并允许 TickHub。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { openBatterySettings(context) }) { Text("电池无限制设置") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openAppDetails(context) }) { Text("应用详情页（找自启动）") }
            }
        }
        Spacer(Modifier.height(16.dp))

        // exact alarm permission (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
            val canExact = alarmManager?.canScheduleExactAlarms() == true
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("精确闹钟", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (canExact) "✓ 已允许精确闹钟，触发更准时" else "⚠ 未允许精确闹钟，触发可能延迟数分钟",
                        color = if (canExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!canExact) {
                        Button(onClick = { openExactAlarmSettings(context) }) { Text("去开启精确闹钟") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("网络恢复等待时长", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("夜间手机睡眠可能断网。到点时 APP 会先唤醒，等待网络恢复后再触发。当前：$waitMinutes 分钟", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Row {
                    listOf(1, 3, 5, 10, 15).forEach { m ->
                        OutlinedButton(
                            onClick = { vm.setNetworkWait(m) },
                            modifier = Modifier.padding(end = 4.dp)
                        ) { Text(if (m == waitMinutes) "[$m]" else m.toString()) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
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
