package com.lucaslu.tickhub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(state: LoginUiState, vm: AppViewModel, configured: Boolean) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var loggingIn by remember { mutableStateOf(false) }
    var showDeviceFlow by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("TickHub", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "用 GitHub 登录，把手机变成 Actions 调度中心",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))

        // ---- Primary path: PAT ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("方式一：粘贴 Token 登录（推荐）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "在 GitHub → Settings → Developer settings → Personal access tokens 生成一个 Classic Token，勾选 repo 权限，复制粘贴到下方。一次登录，长期有效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; tokenError = null },
                    label = { Text("GitHub Personal Access Token") },
                    placeholder = { Text("ghp_...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    supportingText = {
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(if (showToken) "隐藏" else "显示")
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        loggingIn = true
                        tokenError = null
                        vm.loginWithToken(token) { ok, msg ->
                            loggingIn = false
                            if (!ok) tokenError = msg
                        }
                    },
                    enabled = !loggingIn && token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loggingIn) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(4.dp))
                    }
                    Text(if (loggingIn) "验证中…" else "登录")
                }
                tokenError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { openBrowser(context, "https://github.com/settings/tokens/new?scopes=repo&description=TickHub") }) {
                    Text("打开 GitHub 生成 Token 页面 →")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showDeviceFlow = !showDeviceFlow }) {
            Text(if (showDeviceFlow) "收起备用方式" else "方式二：设备码登录（备用，无需 Token）")
        }

        // ---- Fallback: Device Flow ----
        if (showDeviceFlow) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("设备码登录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    if (state.userCode == null && state.error == null) {
                        Button(onClick = { vm.startDeviceFlow() }, enabled = !state.loading && configured) {
                            if (state.loading) {
                                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.padding(4.dp))
                            }
                            Text(if (state.loading) "正在请求设备码…" else "获取设备码")
                        }
                    }

                    state.userCode?.let { code ->
                        Text("在浏览器输入此设备码", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            code,
                            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = {
                            copyToClipboard(context, code)
                            Toast.makeText(context, "设备码已复制", Toast.LENGTH_SHORT).show()
                        }) { Text("复制设备码") }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { openBrowser(context, state.verificationUri ?: "https://github.com/login/device") }) {
                            Text("打开 GitHub 授权页面")
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { vm.checkAuthorizationNow() }) { Text("我已完成授权，检查") }
                        Spacer(Modifier.height(12.dp))
                        Text(state.status, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "授权页显示「GitHub CLI」属正常现象。若提示网络不可达，检查 VPN 后点上方按钮重试，无需重新获取设备码。",
                            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.startDeviceFlow() }) { Text("重新获取设备码") }
                    }

                    state.error?.let { err ->
                        Spacer(Modifier.height(12.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Button(onClick = { vm.startDeviceFlow() }) { Text("重试") }
                    }
                }
            }
        }

        if (!configured) {
            Spacer(Modifier.height(12.dp))
            Text("设备码登录未配置 Client ID，请使用 Token 登录。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
