package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.util.t

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleEventEffect
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
    // Domain ViewModels share one AppContainer, so state/instances stay consistent.
    val authVm: AuthViewModel = viewModel()
    val repoVm: RepoViewModel = viewModel()
    val ruleVm: RuleViewModel = viewModel()
    val historyVm: HistoryViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    val loginState by authVm.login.collectAsState()
    val themeMode by authVm.themeMode.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showLogs by rememberSaveable { mutableStateOf(false) }

    val darkTheme = when (themeMode) {
        0 -> false
        1 -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    TickHubTheme(darkTheme = darkTheme) {
        // Process-scoped (NOT rememberSaveable): saved state survives process
        // death, which would skip catch-up / alarm re-registration after the OS
        // kills us. Re-running on config change is idempotent and cheap.
        val startupDone = remember { mutableStateOf(false) }
        LaunchedEffect(loginState.isLoggedIn) {
            if (!loginState.isLoggedIn) {
                // Reset on logout so a different account re-runs alarm setup.
                startupDone.value = false
                return@LaunchedEffect
            }
            if (!startupDone.value) {
                startupDone.value = true
                // Serialized: timezone resync -> alarms -> catch-up. Holiday fetch
                // only on opt-in toggle or once per year (Dec 17 + catch-up fallback).
                settingsVm.onAppReady()
                settingsVm.maybeAnnualHolidayRefresh()
            }
        }

        // Detect timezone / system-time changes that happen while the app is
        // foregrounded (flight mode, manual clock change, travel). onAppReady is
        // idempotent: timezone resync -> alarms -> catch-up.
        LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            if (loginState.isLoggedIn) settingsVm.onAppReady()
        }

        if (!loginState.isLoggedIn) {
            LoginScreen(state = loginState, vm = authVm, configured = authVm.clientConfigured())
            return@TickHubTheme
        }

        // Single-layer structure: grey root, full-screen content, floating translucent tab bar.
        // (A nested Scaffold here used to paint a white surface over status bar / tab area.)
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (tab) {
                0 -> ReposScreen(repoVm)
                1 -> RulesScreen(ruleVm)
                2 -> HistoryScreen(historyVm)
                3 -> SettingsScreen(settingsVm, onOpenLogs = { showLogs = true })
            }

            if (showLogs) {
                LogScreen(onBack = { showLogs = false })
            } else {
                // Bottom bar: white surface extends all the way down over the gesture strip,
                // with a hairline divider so white cards don't merge into the bar.
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                ) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 1.dp
                        )
                        NavigationBar(
                            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            val items = listOf(t("仓库"), t("规则"), t("历史"), t("设置"))
                            items.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = {
                                        tab = index
                                        com.lucaslu.tickhub.util.Haptics.tick(context)
                                    },
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
                }
            }
        }
    }
}
