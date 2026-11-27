package com.lucaslu.tickhub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.domain.RuleEngine
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.network.TokenPoll
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.util.AppLogger
import com.lucaslu.tickhub.scheduler.AlarmScheduler
import com.lucaslu.tickhub.scheduler.DispatchEngine
import com.lucaslu.tickhub.scheduler.DispatchWorker
import java.time.LocalDateTime
import kotlinx.coroutines.delay
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
    val loading: Boolean = false
)

data class RepoUiState(
    val scanning: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MANUAL_RULE_ID = "manual" // pseudo rule id for Repos-tab triggers
    }

    private val api = GitHubApi()
    private val tokenStore = TokenStore(app)
    private val repository = AppRepository(app, api)
    private val alarmScheduler = AlarmScheduler(app)
    private val dispatchEngine = DispatchEngine(app)

    val snapshot = repository.snapshot
    val rules = repository.rules
    val logs = repository.logs
    val networkWaitMinutes = repository.networkWaitMinutes
    val loggedInUser = repository.loggedInUser
    val avatarUrl = repository.avatarUrl
    val runningExecutions = repository.runningExecutions
    val showExecutionStatus = repository.showExecutionStatus
    val runTrackingEnabled = repository.runTrackingEnabled
    val hapticsEnabled = repository.hapticsEnabled

    // Load persisted prefs SYNCHRONOUSLY at construction so the very first frame
    // already shows the saved order / switch states (no "flash to default" on cold start).
    private val _orders = MutableStateFlow<Map<String, List<String>>>(
        kotlinx.coroutines.runBlocking {
            listOf("repos", "rules", "logs").associateWith { repository.readOrder(it) }
        }
    )
    val orders: StateFlow<Map<String, List<String>>> = _orders.asStateFlow()

    private val _showExecutionStatus = MutableStateFlow(kotlinx.coroutines.runBlocking { repository.showExecutionStatusOnce() })
    val showExecutionStatusFlow: StateFlow<Boolean> = _showExecutionStatus.asStateFlow()
    private val _runTrackingEnabled = MutableStateFlow(kotlinx.coroutines.runBlocking { repository.runTrackingOnce() })
    val runTrackingFlow: StateFlow<Boolean> = _runTrackingEnabled.asStateFlow()
    private val _hapticsEnabled = MutableStateFlow(kotlinx.coroutines.runBlocking { repository.hapticsOnce() })
    val hapticsFlow: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()
    private val _holidayEnabled = MutableStateFlow(kotlinx.coroutines.runBlocking { repository.holidayEnabledOnce() })
    val holidayEnabledFlow: StateFlow<Boolean> = _holidayEnabled.asStateFlow()

    init {
        // load app language preference (independent of system locale)
        viewModelScope.launch { com.lucaslu.tickhub.util.L10n.load(getApplication()) }
        viewModelScope.launch {
            repository.hapticsEnabled.collect { com.lucaslu.tickhub.util.Haptics.enabled = it }
        }
        // keep sync-loaded values fresh if prefs change elsewhere
        viewModelScope.launch { repository.showExecutionStatus.collect { _showExecutionStatus.value = it } }
        viewModelScope.launch { repository.runTrackingEnabled.collect { _runTrackingEnabled.value = it } }
        viewModelScope.launch { repository.hapticsEnabled.collect { _hapticsEnabled.value = it } }
        viewModelScope.launch { repository.holidayEnabled.collect { _holidayEnabled.value = it } }
    }

    private val _login = MutableStateFlow(LoginUiState(isLoggedIn = tokenStore.getToken() != null))
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    private val _repo = MutableStateFlow(RepoUiState())
    val repo: StateFlow<RepoUiState> = _repo.asStateFlow()

    // 0=light, 1=dark, 2=system
    private val _themeMode = MutableStateFlow(2)
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    fun cycleTheme() { _themeMode.value = (_themeMode.value + 1) % 3 }

    fun clientConfigured(): Boolean = api.isClientConfigured()

    fun holidayEnabledOnce(): Boolean = _holidayEnabled.value

    fun startDeviceFlow() {
        _login.value = LoginUiState(status = "正在请求设备码…", loading = true)
        viewModelScope.launch {
            when (val result = api.requestDeviceCode()) {
                is ApiResult.Error -> {
                    AppLogger.e("Auth", "device code request failed: ${result.message}")
                    _login.value = LoginUiState(error = result.message)
                }
                is ApiResult.Success -> {
                    val device = result.data
                    AppLogger.i("Auth", "device code obtained, expiresIn=${device.expiresIn}s interval=${device.interval}s")
                    _login.value = LoginUiState(
                        userCode = device.userCode,
                        verificationUri = device.verificationUri,
                        status = "请在浏览器输入设备码完成授权，完成后本 APP 会自动进入，无需其他操作",
                        loading = false
                    )
                    pollLoop(device.deviceCode, device.interval.coerceAtLeast(5), device.expiresIn)
                }
            }
        }
    }

    private suspend fun pollLoop(deviceCode: String, intervalSec: Int, expiresIn: Int) {
        val deadline = System.currentTimeMillis() + expiresIn * 1000L
        var currentInterval = intervalSec
        var consecutiveNetworkErrors = 0
        while (System.currentTimeMillis() < deadline) {
            delay(currentInterval * 1000L)
            when (val result = api.pollToken(deviceCode)) {
                is ApiResult.Error -> {
                    // Unexpected API-layer error (parse failure etc). Treat as transient too,
                    // but do not abort - device code is still valid until deadline.
                    consecutiveNetworkErrors++
                    AppLogger.w("Auth", "poll error (transient #$consecutiveNetworkErrors): ${result.message}")
                    _login.value = _login.value.copy(
                        status = "正在等待授权完成，登录会自动继续，请稍候…",
                        error = null
                    )
                }
                is ApiResult.Success -> {
                    when (val poll = result.data) {
                        is TokenPoll.Success -> {
                            AppLogger.i("Auth", "token obtained, fetching user")
                            tokenStore.saveToken(poll.token)
                            val userResult = api.getCurrentUser(poll.token)
                            val loginName = if (userResult is ApiResult.Success) userResult.data.login else "GitHub 用户"
                            val avatarUrl = if (userResult is ApiResult.Success) userResult.data.avatarUrl else null
                            repository.saveUser(loginName, avatarUrl)
                            AppLogger.i("Auth", "LOGIN OK user=$loginName")
                            _login.value = LoginUiState(isLoggedIn = true, status = "登录成功：" + loginName)
                            return
                        }
                        TokenPoll.Pending -> {
                            if (consecutiveNetworkErrors > 0) {
                                consecutiveNetworkErrors = 0
                                _login.value = _login.value.copy(status = "正在确认授权结果，请稍候…")
                            }
                        }
                        TokenPoll.SlowDown -> currentInterval += 5
                        TokenPoll.NetworkError -> {
                            consecutiveNetworkErrors++
                            AppLogger.d("Auth", "poll network unreachable (#$consecutiveNetworkErrors), retrying")
                            _login.value = _login.value.copy(
                                status = "正在等待授权完成，登录会自动继续，请稍候…",
                                error = null
                            )
                        }
                        TokenPoll.Expired -> {
                            _login.value = _login.value.copy(error = "设备码已过期，请重新发起", loading = false)
                            return
                        }
                        TokenPoll.Denied -> {
                            _login.value = _login.value.copy(error = "你拒绝了授权", loading = false)
                            return
                        }
                    }
                }
            }
        }
        _login.value = _login.value.copy(error = "授权超时，请重试", loading = false)
    }

    fun logout() {
        tokenStore.clearSync() // synchronous so token cannot survive a kill before write
        viewModelScope.launch {
            // Cancel alarms/work BEFORE clearing rules — need the rule ids first.
            repository.getRulesOnce().forEach { alarmScheduler.cancel(it.id) }
            com.lucaslu.tickhub.scheduler.RunTrackerWorker.cancelAll(getApplication())
            androidx.work.WorkManager.getInstance(getApplication()).cancelAllWorkByTag("manual")
            repository.clearAllUserData()
            _login.value = LoginUiState()
        }
    }

    fun scanRepos() {
        val token = tokenStore.getToken()
        if (token == null) {
            _repo.value = RepoUiState(error = "未登录")
            return
        }
        _repo.value = RepoUiState(scanning = true, message = "正在扫描仓库与工作流…")
        viewModelScope.launch {
            when (val result = repository.scan(token)) {
                is ApiResult.Error -> {
                    if (result.isUnauthorized) {
                        // Token revoked: sign out so the user can re-authorize.
                        tokenStore.clearSync()
                        _login.value = LoginUiState()
                    } else {
                        val msg = if (result.isRateLimited) "GitHub 访问频率受限，请稍后再试" else result.message
                        _repo.value = RepoUiState(error = msg)
                    }
                }
                is ApiResult.Success -> {
                    val msg = if (result.data == 0) "扫描完成" else "扫描完成，" + "${result.data}" + " 个仓库的工作流读取失败"
                    _repo.value = RepoUiState(message = msg)
                }
            }
        }
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Pull-to-refresh: refresh avatar + managed repos only, no full scan. */
    fun refreshProfile() {
        if (_refreshing.value) return
        val token = tokenStore.getToken() ?: return
        _refreshing.value = true
        viewModelScope.launch {
            val result = repository.refreshManaged(token)
            if (result is ApiResult.Error) _repo.value = _repo.value.copy(error = result.message)
            _refreshing.value = false
        }
    }

    fun toggleWorkflow(id: Long, selected: Boolean) {
        viewModelScope.launch { repository.setSelected(id, selected) }
    }

    fun toggleManaged(repoId: Long, managed: Boolean) {
        viewModelScope.launch { repository.setManaged(repoId, managed) }
    }

    fun saveRule(rule: Rule) {
        viewModelScope.launch {
            val (next, effective) = repository.computeNextFire(rule, LocalDateTime.now())
            val base = next?.let { RuleEngine.toEpochMillis(it) }
            val finalRule = rule.copy(
                nextFireAt = base,
                effectiveFireAt = effective,
                createdAt = if (rule.createdAt == 0L) System.currentTimeMillis() else rule.createdAt
            )
            repository.saveRule(finalRule)
            alarmScheduler.cancel(rule.id)
            if (finalRule.status == RuleStatus.ENABLED && effective != null && base != null) {
                alarmScheduler.scheduleWithSlot(finalRule, triggerAt = effective, plannedAt = base)
            }
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            alarmScheduler.cancel(id)
            repository.deleteRule(id)
        }
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setRuleEnabled(id, enabled)
            val rule = repository.getRuleOnce(id)
            if (rule == null) return@launch
            if (enabled) {
                val trigger = rule.effectiveFireAt ?: rule.nextFireAt ?: return@launch
                val base = rule.nextFireAt ?: trigger
                alarmScheduler.scheduleWithSlot(rule, triggerAt = trigger, plannedAt = base)
            } else alarmScheduler.cancel(id)
        }
    }

    /** Register alarms for all enabled rules (called on app start). */
    fun syncAlarms() {
        viewModelScope.launch {
            val enabled = repository.getRulesOnce().filter { it.status == RuleStatus.ENABLED }
            AppLogger.i("App", "syncAlarms: ${enabled.size} enabled rules")
            enabled.forEach { alarmScheduler.schedule(it.copy(nextFireAt = it.effectiveFireAt ?: it.nextFireAt)) }
        }
    }

    /** Catch up any rules whose scheduled time has passed but were not fired. */
    fun catchUpMissedRules() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val rules = repository.getRulesOnce().filter { it.status == RuleStatus.ENABLED }
            val logs = repository.getLogsOnce()
            AppLogger.i("App", "catchUp scan: ${rules.size} enabled rules, ${logs.size} logs")
            for (rule in rules) {
                val planned = rule.nextFireAt ?: continue
                if (planned > now) continue // not due yet
                // Already attempted this slot? Any log (success OR failure) counts:
                // a failure log means either a retry alarm is pending or retries were
                // exhausted and the rule advanced. Either way, no catch-up needed —
                // excluding FAILED here used to double-dispatch with the retry alarm.
                val alreadyAttempted = logs.any {
                    it.ruleId == rule.id && it.plannedAt == planned
                }
                if (alreadyAttempted) continue
                // enqueue as makeup dispatch
                AppLogger.w("App", "catchUp MAKEUP rule=${rule.id.take(8)} missed at=$planned")
                DispatchWorker.enqueue(getApplication(), rule.id, planned, isMakeup = true)
            }
            repository.setLastCatchUpAt(now)
        }
    }

    /** Manually trigger a rule now. DispatchEngine handles logging and advancing next fire. */
    fun triggerNow(rule: Rule) {
        DispatchWorker.enqueue(getApplication(), rule.id, System.currentTimeMillis(), isMakeup = false)
    }

    fun removeRepo(repoId: Long) {
        viewModelScope.launch { repository.removeRepo(repoId) }
    }

    fun deleteLog(id: String) {
        viewModelScope.launch { repository.deleteLog(id) }
    }

    fun setShowExecutionStatus(enabled: Boolean) {
        viewModelScope.launch { repository.setShowExecutionStatus(enabled) }
    }

    fun setRunTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setRunTrackingEnabled(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setHapticsEnabled(enabled) }
    }

    fun setLanguage(lang: com.lucaslu.tickhub.util.L10n.Lang) {
        viewModelScope.launch { com.lucaslu.tickhub.util.L10n.set(getApplication(), lang) }
    }

    /** China-only statutory calendar opt-in. Turning it on immediately fetches
     *  current + next year data (cached thereafter); turning off stops refreshes. */
    fun setHolidayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setHolidayEnabled(enabled)
            _holidayEnabled.value = enabled
            if (enabled) refreshHolidays()
        }
    }

    fun saveOrder(kind: String, ids: List<String>) {
        _orders.value = _orders.value.toMutableMap().apply { put(kind, ids) }
        viewModelScope.launch { repository.saveOrder(kind, ids) }
    }

    suspend fun readOrder(kind: String): List<String> = _orders.value[kind] ?: repository.readOrder(kind)

    fun <T> applyOrder(list: List<T>, order: List<String>, idOf: (T) -> String): List<T> =
        repository.applyOrder(list, order, idOf)

    /** Manually trigger a single workflow directly (not via a rule). */
    fun triggerWorkflowNow(repoFullName: String, workflowId: Long, workflowName: String) {
        viewModelScope.launch {
            val token = tokenStore.getToken() ?: return@launch
            val parts = repoFullName.split("/")
            if (parts.size != 2) return@launch
            val ref = repository.getSnapshotOnce().repos.firstOrNull { it.fullName == repoFullName }?.defaultBranch?.takeIf { it.isNotBlank() } ?: "main"
            when (val result = api.dispatchWorkflow(token, parts[0], parts[1], workflowId, ref)) {
                is ApiResult.Success -> {
                    AppLogger.i("Manual", "trigger OK wf=$workflowName repo=$repoFullName")
                    val logId = java.util.UUID.randomUUID().toString()
                    val now = System.currentTimeMillis()
                    repository.addLog(
                        com.lucaslu.tickhub.data.TriggerLog(
                            id = logId,
                            ruleId = MANUAL_RULE_ID,
                            workflowName = workflowName,
                            workflowNames = listOf(workflowName),
                            repoFullName = repoFullName,
                            plannedAt = now,
                            firedAt = now,
                            status = com.lucaslu.tickhub.data.TriggerStatus.SUCCESS,
                            httpCode = result.data,
                            tracking = true,
                            trackWorkflowIds = listOf(workflowId)
                        )
                    )
                    // Same real-duration tracking as rule triggers, so the history
                    // card shows a live-growing timer until the run completes.
                    com.lucaslu.tickhub.scheduler.RunTrackerWorker.enqueue(getApplication(), logId, MANUAL_RULE_ID)
                }
                is ApiResult.Error -> AppLogger.e("Manual", "trigger FAILED wf=$workflowName: ${result.message}")
            }
        }
    }

    /** Refresh holiday data from GitHub (best effort). */
    fun refreshHolidays() {
        viewModelScope.launch {
            val ok = com.lucaslu.tickhub.data.HolidayCalendar.refresh(getApplication())
            AppLogger.i("Holiday", "refresh current+next year result=$ok")
            if (ok) repository.setHolidayAnnualFetchYear(java.time.LocalDate.now().year)
        }
    }

    /** Fetch next-year holiday data once a year after Dec 17 (notices usually
     *  published by then). No pull on ordinary app opens. Manual toggle on/off
     *  already triggers a fetch via setHolidayEnabled. */
    fun maybeAnnualHolidayRefresh() {
        viewModelScope.launch {
            if (!repository.holidayEnabledOnce()) return@launch
            val today = java.time.LocalDate.now()
            if (today.monthValue != 12 || today.dayOfMonth < 17) return@launch
            if (repository.holidayAnnualFetchYearOnce() >= today.year) return@launch
            AppLogger.i("Holiday", "annual Dec-17 refresh for ${today.year + 1}")
            com.lucaslu.tickhub.data.HolidayCalendar.refresh(getApplication())
            repository.setHolidayAnnualFetchYear(today.year)
        }
    }
}
