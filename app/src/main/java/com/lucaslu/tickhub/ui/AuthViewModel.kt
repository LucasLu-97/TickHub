package com.lucaslu.tickhub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.network.TokenPoll
import com.lucaslu.tickhub.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** GitHub OAuth device-flow login and logout. All state lives in AppContainer. */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val c = AppContainer.get(app)

    val login: StateFlow<LoginUiState> get() = c.login
    val themeMode: StateFlow<Int> get() = c.themeMode

    fun cycleTheme() { c.themeMode.value = (c.themeMode.value + 1) % 3 }

    fun clientConfigured(): Boolean = c.api.isClientConfigured()

    fun setLanguage(lang: com.lucaslu.tickhub.util.L10n.Lang) {
        viewModelScope.launch { com.lucaslu.tickhub.util.L10n.set(getApplication(), lang) }
    }

    fun startDeviceFlow() {
        c.login.value = LoginUiState(status = "正在请求设备码…", loading = true)
        viewModelScope.launch {
            when (val result = c.api.requestDeviceCode()) {
                is ApiResult.Error -> {
                    AppLogger.e("Auth", "device code request failed: ${result.message}")
                    c.login.value = LoginUiState(error = result.message)
                }
                is ApiResult.Success -> {
                    val device = result.data
                    AppLogger.i("Auth", "device code obtained, expiresIn=${device.expiresIn}s interval=${device.interval}s")
                    c.login.value = LoginUiState(
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
            when (val result = c.api.pollToken(deviceCode)) {
                is ApiResult.Error -> {
                    consecutiveNetworkErrors++
                    AppLogger.w("Auth", "poll error (transient #$consecutiveNetworkErrors): ${result.message}")
                    c.login.value = c.login.value.copy(
                        status = "正在等待授权完成，登录会自动继续，请稍候…",
                        error = null
                    )
                }
                is ApiResult.Success -> {
                    when (val poll = result.data) {
                        is TokenPoll.Success -> {
                            AppLogger.i("Auth", "token obtained, fetching user")
                            c.tokenStore.saveToken(poll.token)
                            val userResult = c.api.getCurrentUser(poll.token)
                            val loginName = if (userResult is ApiResult.Success) userResult.data.login else "GitHub 用户"
                            val avatarUrl = if (userResult is ApiResult.Success) userResult.data.avatarUrl else null
                            c.repository.saveUser(loginName, avatarUrl)
                            AppLogger.i("Auth", "LOGIN OK user=$loginName")
                            c.login.value = LoginUiState(isLoggedIn = true, status = "登录成功：" + loginName, statusKey = "登录成功：{0}", statusArgs = listOf(loginName))
                            return
                        }
                        TokenPoll.Pending -> {
                            if (consecutiveNetworkErrors > 0) {
                                consecutiveNetworkErrors = 0
                                c.login.value = c.login.value.copy(status = "正在确认授权结果，请稍候…")
                            }
                        }
                        TokenPoll.SlowDown -> currentInterval += 5
                        TokenPoll.NetworkError -> {
                            consecutiveNetworkErrors++
                            AppLogger.d("Auth", "poll network unreachable (#$consecutiveNetworkErrors), retrying")
                            c.login.value = c.login.value.copy(
                                status = "正在等待授权完成，登录会自动继续，请稍候…",
                                error = null
                            )
                        }
                        TokenPoll.Expired -> {
                            c.login.value = c.login.value.copy(error = "设备码已过期，请重新发起", loading = false)
                            return
                        }
                        TokenPoll.Denied -> {
                            c.login.value = c.login.value.copy(error = "你拒绝了授权", loading = false)
                            return
                        }
                    }
                }
            }
        }
        c.login.value = c.login.value.copy(error = "授权超时，请重试", loading = false)
    }

    fun logout() = c.logout()
}
