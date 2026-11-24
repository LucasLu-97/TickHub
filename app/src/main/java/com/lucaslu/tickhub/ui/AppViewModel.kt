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

    private val _login = MutableStateFlow(LoginUiState(isLoggedIn = tokenStore.getToken() != null))
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    private val _repo = MutableStateFlow(RepoUiState())
    val repo: StateFlow<RepoUiState> = _repo.asStateFlow()

    // 0=light, 1=dark, 2=system
    private val _themeMode = MutableStateFlow(2)
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    fun cycleTheme() { _themeMode.value = (_themeMode.value + 1) % 3 }

    fun clientConfigured(): Boolean = api.isClientConfigured()

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
                        status = "请在浏览器打开 ${device.verificationUri} 并输入设备码",
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
                        status = "网络暂时不可达，正在重试（$consecutiveNetworkErrors）…请保持本 APP 在前台或切回来",
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
                            repository.saveUser(loginName)
                            AppLogger.i("Auth", "LOGIN OK user=$loginName")
                            _login.value = LoginUiState(isLoggedIn = true, status = "登录成功：$loginName")
                            return
                        }
                        TokenPoll.Pending -> {
                            if (consecutiveNetworkErrors > 0) {
                                consecutiveNetworkErrors = 0
                                _login.value = _login.value.copy(status = "已重新连接，等待你在浏览器完成授权…")
                            }
                        }
                        TokenPoll.SlowDown -> currentInterval += 5
                        TokenPoll.NetworkError -> {
                            consecutiveNetworkErrors++
                            AppLogger.d("Auth", "poll network unreachable (#$consecutiveNetworkErrors), retrying")
                            _login.value = _login.value.copy(
                                status = "网络暂时不可达，正在重试（$consecutiveNetworkErrors）…请切回本 APP 或保持前台",
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
        tokenStore.clear()
        viewModelScope.launch {
            repository.clearUser()
            repository.clearSnapshot()
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
                is ApiResult.Error -> _repo.value = RepoUiState(error = result.message)
                is ApiResult.Success -> {
                    val msg = if (result.data == 0) "扫描完成" else "扫描完成，${result.data} 个仓库的工作流读取失败"
                    _repo.value = RepoUiState(message = msg)
                }
            }
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
            val next = RuleEngine.computeNextFire(rule, LocalDateTime.now())
            val finalRule = rule.copy(
                nextFireAt = next?.let { RuleEngine.toEpochMillis(it) },
                createdAt = if (rule.createdAt == 0L) System.currentTimeMillis() else rule.createdAt
            )
            repository.saveRule(finalRule)
            alarmScheduler.cancel(rule.id)
            if (finalRule.status == RuleStatus.ENABLED) alarmScheduler.schedule(finalRule)
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
            if (enabled) alarmScheduler.schedule(rule) else alarmScheduler.cancel(id)
        }
    }

    /** Register alarms for all enabled rules (called on app start). */
    fun syncAlarms() {
        viewModelScope.launch {
            val enabled = repository.getRulesOnce().filter { it.status == RuleStatus.ENABLED }
            AppLogger.i("App", "syncAlarms: ${enabled.size} enabled rules")
            enabled.forEach { alarmScheduler.schedule(it) }
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
                // already fired for this planned slot?
                val alreadyFired = logs.any {
                    it.ruleId == rule.id && it.plannedAt == planned &&
                        it.status != com.lucaslu.tickhub.data.TriggerStatus.FAILED
                }
                if (alreadyFired) continue
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

    /** Manually trigger a single workflow directly (not via a rule). */
    fun triggerWorkflowNow(repoFullName: String, workflowId: Long, workflowName: String) {
        viewModelScope.launch {
            val token = tokenStore.getToken() ?: return@launch
            val parts = repoFullName.split("/")
            if (parts.size != 2) return@launch
            when (val result = api.dispatchWorkflow(token, parts[0], parts[1], workflowId, "main")) {
                is ApiResult.Success -> {
                    AppLogger.i("Manual", "trigger OK wf=$workflowName repo=$repoFullName")
                    repository.addLog(
                        com.lucaslu.tickhub.data.TriggerLog(
                            id = java.util.UUID.randomUUID().toString(),
                            ruleId = "manual",
                            workflowName = workflowName,
                            repoFullName = repoFullName,
                            plannedAt = System.currentTimeMillis(),
                            firedAt = System.currentTimeMillis(),
                            status = com.lucaslu.tickhub.data.TriggerStatus.SUCCESS,
                            httpCode = result.data
                        )
                    )
                }
                is ApiResult.Error -> AppLogger.e("Manual", "trigger FAILED wf=$workflowName: ${result.message}")
            }
        }
    }

    /** Refresh holiday data from GitHub (best effort). */
    fun refreshHolidays() {
        viewModelScope.launch {
            val year = java.time.LocalDate.now().year
            val ok = com.lucaslu.tickhub.data.HolidayCalendar.refresh(getApplication(), year)
            AppLogger.i("Holiday", "refresh $year result=$ok")
        }
    }
}
