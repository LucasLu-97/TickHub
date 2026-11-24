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
                    activeDeviceCode = device.deviceCode
                    _login.value = LoginUiState(
                        userCode = device.userCode,
                        verificationUri = device.verificationUri,
                        status = "请在浏览器打开 ${device.verificationUri} 并输入设备码",
                        loading = false
                    )
                    pollJob?.cancel()
                    pollJob = viewModelScope.launch { pollLoop(device.deviceCode, device.interval.coerceAtLeast(5), device.expiresIn) }
                }
            }
        }
    }

    private var activeDeviceCode: String? = null
    private var pollJob: kotlinx.coroutines.Job? = null

    private suspend fun pollLoop(deviceCode: String, intervalSec: Int, expiresIn: Int) {
        val deadline = System.currentTimeMillis() + expiresIn * 1000L
        var currentInterval = intervalSec
        var networkRetries = 0
        while (System.currentTimeMillis() < deadline) {
            delay(currentInterval * 1000L)
            val result = api.pollToken(deviceCode)
            when (result) {
                is ApiResult.Error -> {
                    // Parse/HTTP error: log but keep polling (transient server hiccups happen)
                    AppLogger.w("Auth", "poll error (will retry): ${result.message}")
                }
                is ApiResult.Success -> when (val poll = result.data) {
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
                    TokenPoll.Pending -> Unit
                    TokenPoll.SlowDown -> currentInterval += 5
                    TokenPoll.NetworkError -> {
                        networkRetries++
                        AppLogger.w("Auth", "poll network unreachable, retry $networkRetries (keep polling)")
                        _login.value = _login.value.copy(
                            error = null,
                            status = "网络暂时不可达，正在重试（第 $networkRetries 次）。你也可以在完成网页授权后点「我已完成授权」"
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
        _login.value = _login.value.copy(error = "授权超时，请重试", loading = false)
    }

    /** Manual check after user finished browser authorization. */
    fun checkAuthorizationNow() {
        val deviceCode = activeDeviceCode ?: return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _login.value = _login.value.copy(status = "正在检查授权状态…", error = null)
            when (val result = api.pollToken(deviceCode)) {
                is ApiResult.Success -> when (val poll = result.data) {
                    is TokenPoll.Success -> {
                        tokenStore.saveToken(poll.token)
                        val userResult = api.getCurrentUser(poll.token)
                        val loginName = if (userResult is ApiResult.Success) userResult.data.login else "GitHub 用户"
                        repository.saveUser(loginName)
                        AppLogger.i("Auth", "LOGIN OK (manual check) user=$loginName")
                        _login.value = LoginUiState(isLoggedIn = true, status = "登录成功：$loginName")
                    }
                    TokenPoll.Pending -> _login.value = _login.value.copy(status = "授权还未完成，请在浏览器确认后再点此按钮", error = null)
                    TokenPoll.NetworkError -> _login.value = _login.value.copy(status = "网络不可达，请检查 VPN/流量后重试", error = null)
                    TokenPoll.Expired -> _login.value = _login.value.copy(error = "设备码已过期，请重新获取", loading = false)
                    TokenPoll.Denied -> _login.value = _login.value.copy(error = "你拒绝了授权", loading = false)
                    TokenPoll.SlowDown -> _login.value = _login.value.copy(status = "请求过快，请稍后再点", error = null)
                }
                is ApiResult.Error -> _login.value = _login.value.copy(error = result.message, loading = false)
            }
        }
    }

    /** Login directly with a Personal Access Token (fastest path). */
    fun loginWithToken(token: String, onResult: (Boolean, String) -> Unit) {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            onResult(false, "Token 不能为空")
            return
        }
        viewModelScope.launch {
            when (val result = api.getCurrentUser(trimmed)) {
                is ApiResult.Error -> {
                    AppLogger.w("Auth", "PAT validation failed: ${result.message}")
                    onResult(false, "Token 无效或网络不可达：${result.message}")
                }
                is ApiResult.Success -> {
                    tokenStore.saveToken(trimmed)
                    repository.saveUser(result.data.login)
                    AppLogger.i("Auth", "LOGIN OK (PAT) user=${result.data.login}")
                    _login.value = LoginUiState(isLoggedIn = true, status = "登录成功：${result.data.login}")
                    onResult(true, result.data.login)
                }
            }
        }
    }

    fun logout() {
        activeDeviceCode = null
        pollJob?.cancel()
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

    fun setNetworkWait(minutes: Int) {
        viewModelScope.launch { repository.setNetworkWaitMinutes(minutes) }
    }
}
