package com.lucaslu.tickhub.scheduler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.data.TriggerLog
import com.lucaslu.tickhub.data.TriggerStatus
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.domain.RuleEngine
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.repository.AppRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDateTime
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Core dispatch execution shared by the alarm Worker and the catch-up path.
 * Flow: wait for network (with timeout) -> POST /dispatches (retry x3) ->
 * log result -> compute next fire -> reschedule.
 */
class DispatchEngine(private val context: Context) {

    private val api = GitHubApi()
    private val tokenStore = TokenStore(context)
    private val repository = AppRepository(context, api)
    private val alarmScheduler = AlarmScheduler(context)

    /** Execute a rule dispatch. Returns the resulting status. */
    suspend fun execute(ruleId: String, isMakeup: Boolean, plannedAt: Long): TriggerStatus {
        val rule = repository.getRuleOnce(ruleId) ?: return TriggerStatus.FAILED
        val token = tokenStore.getToken()
        if (token == null) {
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = "未登录，token 缺失", isMakeup = isMakeup)
            return TriggerStatus.FAILED
        }

        val waitMinutes = repository.getNetworkWaitMinutesOnce()
        val networkOk = waitForNetwork(waitMinutes)
        if (!networkOk) {
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = "等待网络超时（${waitMinutes}分钟）", isMakeup = isMakeup)
            advanceRule(rule)
            return TriggerStatus.FAILED
        }

        // small delay for network stack / DNS to stabilize
        delay(1_500)

        // parse owner/repo from "owner/repo"
        val parts = rule.repoFullName.split("/")
        if (parts.size != 2) {
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = "仓库名无效：${rule.repoFullName}", isMakeup = isMakeup)
            return TriggerStatus.FAILED
        }

        var lastError: String? = null
        var httpCode: Int? = null
        var success = false
        val delays = longArrayOf(1_000, 4_000, 16_000)
        for (attempt in 0..2) {
            when (val result = api.dispatchWorkflow(token, parts[0], parts[1], rule.workflowId, "main")) {
                is ApiResult.Success -> {
                    httpCode = result.data
                    success = true
                    break
                }
                is ApiResult.Error -> {
                    lastError = result.message
                    httpCode = result.code
                    if (attempt < 2) delay(delays[attempt])
                }
            }
        }

        val status = if (success) {
            if (isMakeup) TriggerStatus.MAKEUP else TriggerStatus.SUCCESS
        } else TriggerStatus.FAILED

        addLog(
            rule = rule,
            plannedAt = plannedAt,
            status = status,
            httpCode = httpCode,
            errorMessage = if (success) null else lastError,
            isMakeup = isMakeup
        )

        advanceRule(rule)
        return status
    }

    /** Compute next fire time, persist, and re-register the alarm. */
    private suspend fun advanceRule(rule: Rule) {
        val next = RuleEngine.computeNextFire(rule, LocalDateTime.now())
        if (next == null) {
            // ONCE rule completed
            repository.updateRule(
                rule.copy(
                    status = RuleStatus.COMPLETED,
                    nextFireAt = null,
                    lastFiredAt = System.currentTimeMillis()
                )
            )
            alarmScheduler.cancel(rule.id)
            return
        }
        val updated = repository.updateRule(
            rule.copy(
                nextFireAt = RuleEngine.toEpochMillis(next),
                lastFiredAt = System.currentTimeMillis()
            )
        )
        alarmScheduler.schedule(updated)
    }

    private suspend fun addLog(
        rule: Rule,
        plannedAt: Long,
        status: TriggerStatus,
        httpCode: Int? = null,
        errorMessage: String? = null,
        isMakeup: Boolean = false
    ) {
        repository.addLog(
            TriggerLog(
                id = UUID.randomUUID().toString(),
                ruleId = rule.id,
                workflowName = rule.workflowName,
                plannedAt = plannedAt,
                firedAt = System.currentTimeMillis(),
                status = status,
                httpCode = httpCode,
                errorMessage = errorMessage,
                isMakeup = isMakeup
            )
        )
    }

    /** Wait for an available network connection, up to timeoutMinutes. */
    private suspend fun waitForNetwork(timeoutMinutes: Int): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (hasNetwork(cm)) return true

        val timeoutMs = timeoutMinutes * 60_000L
        return suspendCancellableCoroutine { cont ->
            var finished = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            lateinit var timeoutRunnable: Runnable
            fun finish(result: Boolean) {
                if (finished) return
                finished = true
                handler.removeCallbacks(timeoutRunnable)
                runCatching { cm.unregisterNetworkCallback(callback) }
                if (cont.isActive) cont.resume(result)
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = finish(true)
            }
            timeoutRunnable = Runnable { finish(false) }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                cm.registerNetworkCallback(request, callback)
            } catch (e: Exception) {
                finish(false)
                return@suspendCancellableCoroutine
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)
            cont.invokeOnCancellation { finish(false) }
        }
    }

    private fun hasNetwork(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transportOk = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return hasInternet && transportOk
    }

    /** Acquire a partial wake lock for the given duration (caller must release). */
    fun acquireWakeLock(tag: String, timeoutMs: Long = 15 * 60_000L): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TickHub::$tag").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }
}
