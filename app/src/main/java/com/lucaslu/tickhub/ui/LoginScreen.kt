package com.lucaslu.tickhub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.util.L10n
import com.lucaslu.tickhub.util.t

@Composable
fun LoginScreen(state: LoginUiState, vm: AppViewModel, configured: Boolean) {
    val context = LocalContext.current
    val lang by L10n.lang.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("TickHub", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            t("用 GitHub 登录，把手机变成 Actions 调度中心"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        // language switch — visible on the very first screen for non-Chinese users
        TextButton(onClick = { vm.setLanguage(if (lang == L10n.Lang.EN) L10n.Lang.ZH else L10n.Lang.EN) }) {
            Text(if (lang == L10n.Lang.EN) "中文" else "English")
        }
        Spacer(Modifier.height(24.dp))

        if (!configured) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    t("GitHub Client ID 未注入，请联系开发者。"),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            return@Column
        }

        if (state.userCode == null && state.error == null) {
            Button(onClick = { vm.startDeviceFlow() }, enabled = !state.loading) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(4.dp))
                }
                Text(if (state.loading) t("正在请求设备码…") else t("用 GitHub 登录"))
            }
        }

        state.userCode?.let { code ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("在浏览器输入此设备码"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        code,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        copyToClipboard(context, code)
                        Toast.makeText(context, L10n.tNow("设备码已复制"), Toast.LENGTH_SHORT).show()
                    }) {
                        Text(t("复制设备码"))
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { openBrowser(context, state.verificationUri ?: "https://github.com/login/device") }) {
                        Text(t("打开 GitHub 授权页面"))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(8.dp))
                        Text(
                            t(state.status),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("授权页面显示「GitHub CLI」属正常现象——TickHub 使用 GitHub 官方公开登录方式，请放心授权。"),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.startDeviceFlow() }) { Text(t("重新获取设备码")) }
        }

        state.error?.let { err ->
            Spacer(Modifier.height(16.dp))
            Text(t(err), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.startDeviceFlow() }) { Text(t("重试")) }
        }
    }
}

private fun openBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("TickHub device code", text))
}
