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
import kotlinx.coroutines.flow.StateFlow

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Composition only: state holders + use cases own the behaviour. ----
    private val authState = AuthState(tokenStore)
    private val settings = SettingsState(repository)
    private val useCases = AppUseCases(app, repository, tokenStore, alarmScheduler, scope, authState)

    // Ephemeral UI state (auth / repo screen)
    val login: StateFlow<LoginUiState> get() = authState.login
    val repo: StateFlow<RepoUiState> get() = authState.repo
    val refreshing: StateFlow<Boolean> get() = authState.refreshing
    fun setLogin(value: LoginUiState) = authState.setLogin(value)
    fun updateLogin(t: (LoginUiState) -> LoginUiState) = authState.updateLogin(t)
    fun setRepo(value: RepoUiState) = authState.setRepo(value)
    fun updateRepo(t: (RepoUiState) -> RepoUiState) = authState.updateRepo(t)
    fun setRefreshing(value: Boolean) = authState.setRefreshing(value)

    // Settings state: DataStore single source of truth (stateIn, no mirrors)
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

    // Cross-cutting business actions
    fun logout() = useCases.logout()
    fun setLanguage(lang: com.lucaslu.tickhub.util.L10n.Lang) = useCases.setLanguage(lang)

    // ---- Repository-backed observable data ----
    val snapshot = repository.snapshot
    val rules = repository.rules
    val logs = repository.logs
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
