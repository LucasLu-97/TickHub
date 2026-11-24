package com.lucaslu.tickhub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lucaslu.tickhub.util.AppLogger

@Composable
fun LogScreen() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(AppLogger.readAll()) }
    val scroll = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("调试日志", style = MaterialTheme.typography.titleLarge)
        Text("记录闹钟注册/触发、网络等待、dispatch 全过程，可导出分析故障", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        Row {
            Button(onClick = { logText = AppLogger.readAll() }, modifier = Modifier.weight(1f).padding(end = 4.dp)) { Text("刷新") }
            OutlinedButton(onClick = {
                copyToClipboard(context, logText)
                Toast.makeText(context, "日志已复制，可粘贴发送", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text("复制全部") }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = { shareLogFile(context) }, modifier = Modifier.weight(1f).padding(end = 4.dp)) { Text("分享日志文件") }
            OutlinedButton(onClick = {
                AppLogger.clear()
                logText = AppLogger.readAll()
                Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text("清空") }
        }
        Spacer(Modifier.height(12.dp))

        Text(
            logText,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
                .horizontalScroll(rememberScrollState())
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("TickHub debug log", text))
}

private fun shareLogFile(context: Context) {
    val file = AppLogger.getFile()
    if (file == null || !file.exists()) {
        Toast.makeText(context, "暂无日志文件", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "TickHub 调试日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
