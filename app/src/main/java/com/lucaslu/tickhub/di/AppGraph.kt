package com.lucaslu.tickhub.di

import android.app.Application
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.scheduler.AlarmScheduler
import com.lucaslu.tickhub.scheduler.DispatchEngine
import com.lucaslu.tickhub.ui.AppUseCases
import com.lucaslu.tickhub.ui.AuthState
import com.lucaslu.tickhub.ui.LoginUiState
import com.lucaslu.tickhub.ui.RepoUiState
import com.lucaslu.tickhub.ui.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide dependency graph (composition root). Infrastructure is created
 * once; UI state and business actions live in dedicated holders/use cases
 * (AuthState, SettingsState, AppUseCases) — this class only wires them and
 * exposes a thin facade so ViewModel call sites stay simple.
 *
 * Behaviour is identical to the former AppContainer: same singleton instances,
 * same flows. No business logic lives here.
 */
class AppGraph private constructor(val app: Application) {

    val api = GitHubApi()
    val tokenStore = TokenStore(app)
    val repository = AppRepository(app, api)
    val alarmScheduler = AlarmScheduler(app)
    val dispatchEngine = DispatchEngine(app)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val authState = AuthState(tokenStore)
    private val settings = SettingsState(repository)
    private val useCases = AppUseCases(app, repository, tokenStore, alarmScheduler, scope, authState)

    // Ephemeral UI state
    val login: StateFlow<LoginUiState> get() = authState.login
    val repo: StateFlow<RepoUiState> get() = authState.repo
    val refreshing: StateFlow<Boolean> get() = authState.refreshing
    fun setLogin(value: LoginUiState) = authState.setLogin(value)
    fun updateLogin(t: (LoginUiState) -> LoginUiState) = authState.updateLogin(t)
    fun setRepo(value: RepoUiState) = authState.setRepo(value)
    fun updateRepo(t: (RepoUiState) -> RepoUiState) = authState.updateRepo(t)
    fun setRefreshing(value: Boolean) = authState.setRefreshing(value)

    // Settings state (DataStore single source of truth via stateIn)
    val themeMode: StateFlow<Int> get() = settings.themeMode
    val orders: StateFlow<Map<String, List<String>>> get() = settings.orders
    val showExecutionStatusFlow: StateFlow<Boolean> get() = settings.showExecutionStatus
    val runTrackingFlow: StateFlow<Boolean> get() = settings.runTracking
    val hapticsFlow: StateFlow<Boolean> get() = settings.hapticsEnabled
    val holidayEnabledFlow: StateFlow<Boolean> get() = settings.holidayEnabled

    fun cycleTheme() = settings.cycleTheme()
    fun saveOrder(kind: String, ids: List<String>) = settings.saveOrder(kind, ids)

    // Cross-cutting business actions
    fun logout() = useCases.logout()
    fun setLanguage(lang: com.lucaslu.tickhub.util.L10n.Lang) = useCases.setLanguage(lang)

    // Repository-backed observable data
    val snapshot = repository.snapshot
    val rules = repository.rules
    val logs = repository.logs
    val loggedInUser = repository.loggedInUser
    val avatarUrl = repository.avatarUrl
    val runningExecutions = repository.runningExecutions

    companion object {
        const val MANUAL_RULE_ID = "manual" // pseudo rule id for Repos-tab triggers

        @Volatile
        private var instance: AppGraph? = null

        fun get(app: Application): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(app).also { instance = it }
        }
    }
}
