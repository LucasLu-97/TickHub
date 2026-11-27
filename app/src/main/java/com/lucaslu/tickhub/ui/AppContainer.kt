package com.lucaslu.tickhub.ui

import android.app.Application
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.scheduler.AlarmScheduler
import com.lucaslu.tickhub.scheduler.DispatchEngine
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
 *
 * State is exposed read-only: mutations go through explicit methods below so a
 * future ViewModel cannot accidentally bypass the "memory + DataStore" write
 * contracts (e.g. saveOrder / theme persistence).
 */
class AppContainer private constructor(val app: Application) {

    val api = GitHubApi()
    val tokenStore = TokenStore(app)
    val repository = AppRepository(app, api)
    val alarmScheduler = AlarmScheduler(app)
    val dispatchEngine = DispatchEngine(app)

    // ---- Shared UI state (was in AppViewModel) ----
    private val _login = MutableStateFlow(LoginUiState(isLoggedIn = tokenStore.getToken() != null))
    val login: StateFlow<LoginUiState> = _login.asStateFlow()
    fun setLogin(value: LoginUiState) { _login.value = value }
    fun updateLogin(transform: (LoginUiState) -> LoginUiState) { _login.value = transform(_login.value) }

    private val _repo = MutableStateFlow(RepoUiState())
    val repo: StateFlow<RepoUiState> = _repo.asStateFlow()
    fun setRepo(value: RepoUiState) { _repo.value = value }
    fun updateRepo(transform: (RepoUiState) -> RepoUiState) { _repo.value = transform(_repo.value) }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    fun setRefreshing(value: Boolean) { _refreshing.value = value }

    // ONE DataStore read at construction (was eight). runBlocking keeps the first
    // frame free of "flash to default"; runCatching survives corrupt preferences.
    private val initialPrefs = runCatching {
        kotlinx.coroutines.runBlocking { repository.readInitialPrefsOnce() }
    }.getOrDefault(com.lucaslu.tickhub.repository.InitialPrefs())

    // 0=light, 1=dark, 2=system — persisted so the choice survives process death
    private val _themeMode = MutableStateFlow(initialPrefs.themeMode)
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    /** Cycle theme and persist it (single authoritative implementation). */
    fun cycleTheme() {
        val next = (_themeMode.value + 1) % 3
        _themeMode.value = next
        scope.launch { repository.setThemeMode(next) }
    }

    private val _orders = MutableStateFlow(initialPrefs.orders)
    val orders: StateFlow<Map<String, List<String>>> = _orders.asStateFlow()
    /** Single write path: update memory AND persist to DataStore. */
    fun saveOrder(kind: String, ids: List<String>) {
        _orders.value = _orders.value.toMutableMap().apply { put(kind, ids) }
        scope.launch { repository.saveOrder(kind, ids) }
    }

    private val _showExecutionStatusFlow = MutableStateFlow(initialPrefs.showExecution)
    val showExecutionStatusFlow: StateFlow<Boolean> = _showExecutionStatusFlow.asStateFlow()
    private val _runTrackingFlow = MutableStateFlow(initialPrefs.runTracking)
    val runTrackingFlow: StateFlow<Boolean> = _runTrackingFlow.asStateFlow()
    private val _hapticsFlow = MutableStateFlow(initialPrefs.haptics)
    val hapticsFlow: StateFlow<Boolean> = _hapticsFlow.asStateFlow()
    private val _holidayEnabledFlow = MutableStateFlow(initialPrefs.holiday)
    val holidayEnabledFlow: StateFlow<Boolean> = _holidayEnabledFlow.asStateFlow()
    fun setHolidayEnabledState(value: Boolean) { _holidayEnabledFlow.value = value }

    // Process-scoped collector: keeps sync-loaded values fresh when prefs change.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { repository.showExecutionStatus.collect { _showExecutionStatusFlow.value = it } }
        scope.launch { repository.runTrackingEnabled.collect { _runTrackingFlow.value = it } }
        scope.launch { repository.hapticsEnabled.collect { Haptics.enabled = it; _hapticsFlow.value = it } }
        scope.launch { repository.holidayEnabled.collect { _holidayEnabledFlow.value = it } }
    }

    // ---- Repository-backed observable data ----
    val snapshot = repository.snapshot
    val rules = repository.rules
    val logs = repository.logs
    val networkWaitMinutes = repository.networkWaitMinutes
    val loggedInUser = repository.loggedInUser
    val avatarUrl = repository.avatarUrl
    val runningExecutions = repository.runningExecutions

    /** Single logout implementation shared by AuthViewModel and SettingsViewModel. */
    fun logout() {
        tokenStore.clearSync() // synchronous so token cannot survive a kill before write
        scope.launch {
            // Cancel alarms/work BEFORE clearing rules — need the rule ids first.
            repository.getRulesOnce().forEach { alarmScheduler.cancel(it.id) }
            com.lucaslu.tickhub.scheduler.RunTrackerWorker.cancelAll(app)
            androidx.work.WorkManager.getInstance(app).cancelAllWorkByTag("manual")
            repository.clearAllUserData()
            setLogin(LoginUiState())
        }
    }

    companion object {
        const val MANUAL_RULE_ID = "manual" // pseudo rule id for Repos-tab triggers

        @Volatile
        private var instance: AppContainer? = null

        fun get(app: Application): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(app).also { instance = it }
        }
    }
}
