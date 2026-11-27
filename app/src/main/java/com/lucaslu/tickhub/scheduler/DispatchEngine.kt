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
import com.lucaslu.tickhub.util.AppLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    suspend fun markRunning(ruleId: String, startAt: Long) = repository.markRunning(ruleId, startAt)
    suspend fun markDone(ruleId: String) = repository.markDone(ruleId)

    /** Execute a rule dispatch. Returns the resulting status. */
    suspend fun execute(ruleId: String, isMakeup: Boolean, plannedAt: Long): TriggerStatus {
        val rule = repository.getRuleOnce(ruleId)
        if (rule == null) {
            AppLogger.e("Engine", "rule not found: ${ruleId.take(8)}")
            return TriggerStatus.FAILED
        }
        val workflowIds = rule.resolvedWorkflowIds()
        AppLogger.i("Engine", "execute rule=${ruleId.take(8)} wfs=${workflowIds.size} repo=${rule.repoFullName} makeup=$isMakeup")
        val token = tokenStore.getToken()
        if (token == null) {
            AppLogger.e("Engine", "token missing -> abort")
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = com.lucaslu.tickhub.util.L10n.tNow("未登录，token 缺失"), isMakeup = isMakeup)
            return TriggerStatus.FAILED
        }

        val waitMinutes = repository.getNetworkWaitMinutesOnce()
        AppLogger.d("Engine", "network check start (timeout=${waitMinutes}min)")
        val networkOk = waitForNetwork(waitMinutes)
        AppLogger.i("Engine", "network ready=$networkOk")
        if (!networkOk) {
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = com.lucaslu.tickhub.util.L10n.tNow("等待网络超时（") + waitMinutes + com.lucaslu.tickhub.util.L10n.tNow("分钟）"), isMakeup = isMakeup)
            advanceRule(rule)
            return TriggerStatus.FAILED
        }

        // small delay for network stack / DNS to stabilize
        delay(1_500)

        // parse owner/repo from "owner/repo"
        val parts = rule.repoFullName.split("/")
        if (parts.size != 2) {
            AppLogger.e("Engine", "invalid repo name: ${rule.repoFullName}")
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = com.lucaslu.tickhub.util.L10n.tNow("仓库名无效：") + rule.repoFullName, isMakeup = isMakeup)
            return TriggerStatus.FAILED
        }

        // dispatch every selected workflow (concurrently via coroutines), all must succeed
        val results = kotlinx.coroutines.coroutineScope {
            workflowIds.map { wfId ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    var lastError: String? = null
                    var httpCode: Int? = null
                    var success = false
                    val delays = longArrayOf(1_000, 4_000, 16_000)
                    for (attempt in 0..2) {
                        when (val result = api.dispatchWorkflow(token, parts[0], parts[1], wfId, "main")) {
                            is ApiResult.Success -> {
                                httpCode = result.data
                                success = true
                                AppLogger.i("Engine", "dispatch wfId=$wfId SUCCESS http=${result.data} attempt=${attempt + 1}")
                                break
                            }
                            is ApiResult.Error -> {
                                lastError = result.message
                                httpCode = result.code
                                AppLogger.w("Engine", "dispatch wfId=$wfId attempt ${attempt + 1} FAILED http=${result.code} err=${result.message}")
                                if (attempt < 2) delay(delays[attempt])
                            }
                        }
                    }
                    Triple(wfId, success, if (success) httpCode?.toString() else lastError)
                }
            }.map { it.await() }
        }

        val allSuccess = results.isNotEmpty() && results.all { it.second }
        val lastError = results.firstOrNull { !it.second }?.third
        val httpCode = results.firstOrNull()?.third?.toIntOrNull()
        val status = if (allSuccess) {
            if (isMakeup) TriggerStatus.MAKEUP else TriggerStatus.SUCCESS
        } else TriggerStatus.FAILED

        val logId = addLog(
            rule = rule,
            plannedAt = plannedAt,
            status = status,
            httpCode = if (allSuccess) 204 else httpCode,
            errorMessage = if (allSuccess) null else com.lucaslu.tickhub.util.L10n.tNow("工作流 ") + "${results.filter { !it.second }.map { it.first }}" + com.lucaslu.tickhub.util.L10n.tNow(" 触发失败：") + lastError,
            isMakeup = isMakeup
        )

        // Real-duration tracking: mark the log as tracking immediately and let the
        // Worker discover the GitHub run itself on its first poll (~20s later).
        // Previously we queried runs here, but GitHub often hasn't created the run
        // yet right after dispatch, so tracking silently never started and the
        // history card showed no duration.
        if (allSuccess && parts.size == 2 && repository.isRunTrackingEnabledOnce()) {
            repository.markTracking(logId)
            RunTrackerWorker.enqueue(context, logId, rule.id)
            AppLogger.i("Engine", "tracking started log=${logId.take(8)}")
        }

        advanceRule(rule)
        return status
    }

    /** Compute next fire time, persist, and re-register the alarm. */
    private suspend fun advanceRule(rule: Rule) {
        val (next, effective) = repository.computeNextFire(rule, LocalDateTime.now())
        if (next == null || effective == null) {
            AppLogger.i("Engine", "rule=${rule.id.take(8)} COMPLETED (no next fire)")
            repository.updateRule(
                rule.copy(
                    status = RuleStatus.COMPLETED,
                    nextFireAt = null,
                    effectiveFireAt = null,
                    lastFiredAt = System.currentTimeMillis()
                )
            )
            alarmScheduler.cancel(rule.id)
            return
        }
        val drift = (effective - RuleEngine.toEpochMillis(next)) / 60_000
        AppLogger.i("Engine", "rule=${rule.id.take(8)} next base=$next drift=${drift}min")
        val updated = repository.updateRule(
            rule.copy(
                nextFireAt = RuleEngine.toEpochMillis(next),
                effectiveFireAt = effective,
                lastFiredAt = System.currentTimeMillis()
            )
        )
        scheduleAt(updated, effective)
    }

    /** Schedule at the effective (drifted) time. */
    private fun scheduleAt(rule: Rule, effectiveAt: Long) {
        // temporarily swap nextFireAt so AlarmScheduler uses effective time
        alarmScheduler.schedule(rule.copy(nextFireAt = effectiveAt))
    }

    private suspend fun addLog(
        rule: Rule,
        plannedAt: Long,
        status: TriggerStatus,
        httpCode: Int? = null,
        errorMessage: String? = null,
        isMakeup: Boolean = false
    ): String {
        val logId = UUID.randomUUID().toString()
        repository.addLog(
            TriggerLog(
                id = logId,
                ruleId = rule.id,
                workflowName = rule.workflowName,
                workflowNames = rule.workflowNames.ifEmpty { listOfNotNull(rule.workflowName.takeIf { it.isNotBlank() }) },
                repoFullName = rule.repoFullName,
                plannedAt = plannedAt,
                firedAt = System.currentTimeMillis(),
                status = status,
                httpCode = httpCode,
                errorMessage = errorMessage,
                isMakeup = isMakeup,
                durationMs = System.currentTimeMillis() - (repository.runningStartOnce(rule.id) ?: System.currentTimeMillis())
            )
        )
        return logId
    }

    /** Wait for an available network connection, up to timeoutMinutes. */
    private suspend fun waitForNetwork(timeoutMinutes: Int): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (hasNetwork(cm)) return true

        val timeoutMs = timeoutMinutes * 60_000L
        return suspendCancellableCoroutine<Boolean> { cont ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var callback: ConnectivityManager.NetworkCallback? = null
            var timeoutRunnable: Runnable? = null
            var finished = false
            fun finish(result: Boolean) {
                if (finished) return
                finished = true
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                callback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
                if (cont.isActive) cont.resume(result)
            }
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = finish(true)
            }
            timeoutRunnable = Runnable { finish(false) }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                cm.registerNetworkCallback(request, callback!!)
            } catch (e: Exception) {
                finish(false)
                return@suspendCancellableCoroutine
            }
            handler.postDelayed(timeoutRunnable!!, timeoutMs)
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
