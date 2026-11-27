package com.lucaslu.tickhub.ui

import android.app.Application
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.scheduler.AlarmScheduler
import com.lucaslu.tickhub.scheduler.DispatchEngine
import com.lucaslu.tickhub.util.AppLogger
import com.lucaslu.tickhub.util.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoggedIn: Boolean = false,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val status: String = "",
    val error: String? = null,
    val loading: Boolean = false,
    val statusKey: String? = null,
    val statusArgs: List<String> = emptyList()
)

data class RepoUiState(
    val scanning: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val messageKey: String? = null,   // translatable template with {0} placeholders
    val messageArgs: List<String> = emptyList()
)

/**
 * Process-wide shared kernel for all domain ViewModels. Holds the single set of
 * infrastructure components and shared UI state so splitting the old monolithic
 * AppViewModel does NOT change runtime behaviour (same instances, same flows).
 */
class AppContainer private constructor(val app: Application) {

    val api = GitHubApi()
    val tokenStore = TokenStore(app)
    val repository = AppRepository(app, api)
    val alarmScheduler = AlarmScheduler(app)
    val dispatchEngine = DispatchEngine(app)

    // ---- Shared UI state (was in AppViewModel) ----
    val login = MutableStateFlow(LoginUiState(isLoggedIn = tokenStore.getToken() != null))
    val repo = MutableStateFlow(RepoUiState())
    val refreshing = MutableStateFlow(false)
    // 0=light, 1=dark, 2=system
    val themeMode = MutableStateFlow(2)

    private data class PrefsSnapshot(
        val orders: Map<String, List<String>> = emptyMap(),
        val showExecution: Boolean = true,
        val runTracking: Boolean = true,
        val haptics: Boolean = true,
        val holiday: Boolean = false
    )

    // One synchronized read at construction so the first frame shows saved
    // order/switch states (no "flash to default"). A single runBlocking keeps
    // cold-start main-thread IO to a minimum; runCatching survives corrupt data.
    private val initialPrefs = runCatching {
        kotlinx.coroutines.runBlocking {
            PrefsSnapshot(
                orders = listOf("repos", "rules", "logs").associateWith { repository.readOrder(it) },
                showExecution = repository.showExecutionStatusOnce(),
                runTracking = repository.runTrackingOnce(),
                haptics = repository.hapticsOnce(),
                holiday = repository.holidayEnabledOnce()
            )
        }
    }.getOrDefault(PrefsSnapshot())

    val orders = MutableStateFlow(initialPrefs.orders)
    val showExecutionStatusFlow = MutableStateFlow(initialPrefs.showExecution)
    val runTrackingFlow = MutableStateFlow(initialPrefs.runTracking)
    val hapticsFlow = MutableStateFlow(initialPrefs.haptics)
    val holidayEnabledFlow = MutableStateFlow(initialPrefs.holiday)

    // Process-scoped collector: keeps sync-loaded values fresh when prefs change.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { repository.hapticsEnabled.collect { Haptics.enabled = it } }
        scope.launch { repository.showExecutionStatus.collect { showExecutionStatusFlow.value = it } }
        scope.launch { repository.runTrackingEnabled.collect { runTrackingFlow.value = it } }
        scope.launch { repository.hapticsEnabled.collect { hapticsFlow.value = it } }
        scope.launch { repository.holidayEnabled.collect { holidayEnabledFlow.value = it } }
    }

    // ---- Repository-backed observable data ----
    val snapshot = repository.snapshot
    val rules = repository.rules
    val logs = repository.logs
    val networkWaitMinutes = repository.networkWaitMinutes
    val loggedInUser = repository.loggedInUser
    val avatarUrl = repository.avatarUrl
    val runningExecutions = repository.runningExecutions

    companion object {
        const val MANUAL_RULE_ID = "manual" // pseudo rule id for Repos-tab triggers

        @Volatile
        private var instance: AppContainer? = null

        fun get(app: Application): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(app).also { instance = it }
        }
    }
}
