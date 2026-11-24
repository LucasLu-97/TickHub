package com.lucaslu.tickhub.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucaslu.tickhub.ui.theme.TickHubTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent { TickHubApp() }
    }
}

@Composable
private fun TickHubApp() {
    val vm: AppViewModel = viewModel()
    val loginState by vm.login.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    val darkTheme = when (themeMode) {
        0 -> false
        1 -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    TickHubTheme(darkTheme = darkTheme) {
        LaunchedEffect(loginState.isLoggedIn) {
            if (loginState.isLoggedIn) {
                vm.syncAlarms()
                vm.catchUpMissedRules()
                vm.refreshHolidays()
            }
        }

        if (!loginState.isLoggedIn) {
            LoginScreen(state = loginState, vm = vm, configured = vm.clientConfigured())
            return@TickHubTheme
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    val items = listOf("仓库", "规则", "历史", "日志", "设置")
                    items.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {
                                TabIcon(
                                    index = index,
                                    color = if (tab == index) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> ReposScreen(vm)
                    1 -> RulesScreen(vm)
                    2 -> HistoryScreen(vm)
                    3 -> LogScreen()
                    4 -> SettingsScreen(vm)
                }
            }
        }
    }
}
