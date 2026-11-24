package com.lucaslu.tickhub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.network.TokenPoll
import com.lucaslu.tickhub.repository.AppRepository
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

    val snapshot = repository.snapshot
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
                is ApiResult.Error -> _login.value = LoginUiState(error = result.message)
                is ApiResult.Success -> {
                    val device = result.data
                    _login.value = LoginUiState(
                        userCode = device.userCode,
                        verificationUri = device.verificationUri,
                        status = "请在浏览器打开 $device.verificationUri 并输入设备码",
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
        while (System.currentTimeMillis() < deadline) {
            delay(currentInterval * 1000L)
            when (val result = api.pollToken(deviceCode)) {
                is ApiResult.Error -> {
                    _login.value = _login.value.copy(error = result.message, loading = false)
                    return
                }
                is ApiResult.Success -> {
                    when (val poll = result.data) {
                        is TokenPoll.Success -> {
                            tokenStore.saveToken(poll.token)
                            val userResult = api.getCurrentUser(poll.token)
                            val loginName = if (userResult is ApiResult.Success) userResult.data.login else "GitHub 用户"
                            repository.saveUser(loginName)
                            _login.value = LoginUiState(isLoggedIn = true, status = "登录成功：$loginName")
                            return
                        }
                        TokenPoll.Pending -> Unit
                        TokenPoll.SlowDown -> currentInterval += 5
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
}
