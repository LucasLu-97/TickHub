package com.lucaslu.tickhub.ui

import android.app.Application
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.scheduler.AlarmScheduler
import com.lucaslu.tickhub.scheduler.DispatchEngine
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

    // ---- Settings state: DataStore is the single source of truth (no runBlocking,
    //      no manual mirrors). SettingsState derives each StateFlow via stateIn. ----
    private val settings = SettingsState(repository)

    val themeMode: StateFlow<Int> get() = settings.themeMode
    val orders: StateFlow<Map<String, List<String>>> get() = settings.orders
    val showExecutionStatusFlow: StateFlow<Boolean> get() = settings.showExecutionStatus
    val runTrackingFlow: StateFlow<Boolean> get() = settings.runTracking
    val hapticsFlow: StateFlow<Boolean> get() = settings.hapticsEnabled
    val holidayEnabledFlow: StateFlow<Boolean> get() = settings.holidayEnabled

    fun cycleTheme() = settings.cycleTheme()
    fun saveOrder(kind: String, ids: List<String>) = settings.saveOrder(kind, ids)
    /** No-op: stateIn already mirrors the DataStore write back to holidayEnabledFlow. */
    fun setHolidayEnabledState(@Suppress("UNUSED_PARAMETER") value: Boolean) = Unit

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Repository-backed observable data ----
    val snapshot = repository.snapshot
    val rules = repository.rules
    val logs = repository.logs
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
            // Cancel ALL queued dispatch work (scheduled + manual). Previously
            // this targeted a "manual" tag that no WorkRequest ever carried —
            // a silent no-op leaving in-flight work to write back after logout.
            androidx.work.WorkManager.getInstance(app).cancelAllWorkByTag("tickhub_dispatch_all")
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
