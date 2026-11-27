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

    companion object {
        const val MAX_RETRIES = DispatchPolicy.MAX_RETRIES
        const val RETRY_DELAY_MS = DispatchPolicy.RETRY_DELAY_MS
    }

    private val api = GitHubApi()
    private val tokenStore = TokenStore(context)
    private val repository = AppRepository(context, api)
    private val alarmScheduler = AlarmScheduler(context)

    suspend fun markRunning(ruleId: String, startAt: Long) = repository.markRunning(ruleId, startAt)
    suspend fun markDone(ruleId: String) = repository.markDone(ruleId)

    /**
     * Catch up rule slots whose effective time has passed but were not attempted.
     * Shared by app start (SettingsViewModel via AppContainer) and device boot (BootReceiver) so missed
     * slots during shutdown are recovered without requiring the user to open the app.
     * Due check uses effectiveFireAt (drifted); dedup key stays the base slot.
     */
    suspend fun catchUpMissed(): Int {
        val now = System.currentTimeMillis()
        val rules = repository.getRulesOnce().filter { it.status == RuleStatus.ENABLED }
        val logs = repository.getLogsOnce()
        var count = 0
        for (rule in rules) {
            val base = rule.nextFireAt ?: continue
            val dueAt = rule.effectiveFireAt ?: base
            val hasAnyLog = logs.any { it.ruleId == rule.id && it.plannedAt == base }
            when (DispatchPolicy.catchUpAction(now, dueAt, base, rule.retryAt, rule.retryPlannedAt, hasAnyLog)) {
                DispatchPolicy.CatchUpAction.SKIP_NOT_DUE,
                DispatchPolicy.CatchUpAction.SKIP_RETRY_PENDING,
                DispatchPolicy.CatchUpAction.SKIP_ALREADY_ATTEMPTED -> continue
                DispatchPolicy.CatchUpAction.DISPATCH_RETRY_OVERDUE ->
                    AppLogger.w("Engine", "catchUp RETRY overdue rule=${rule.id.take(8)} slot=$base retryAt=${rule.retryAt}")
                DispatchPolicy.CatchUpAction.DISPATCH_MAKEUP ->
                    AppLogger.w("Engine", "catchUp MAKEUP rule=${rule.id.take(8)} due=$dueAt base=$base")
            }
            DispatchWorker.enqueue(context, rule.id, base, isMakeup = true)
            count++
        }
        repository.setLastCatchUpAt(now)
        return count
    }

    /** Execute a rule dispatch. Returns the resulting status. */
    suspend fun execute(ruleId: String, isMakeup: Boolean, plannedAt: Long, advance: Boolean = true): TriggerStatus {
        val rule = repository.getRuleOnce(ruleId)
        if (rule == null) {
            AppLogger.e("Engine", "rule not found: ${ruleId.take(8)}")
            return TriggerStatus.FAILED
        }
        // On a retry of a partially-failed slot, dispatch ONLY the workflows that
        // failed — re-sending already-successful ones causes duplicate check-ins.
        val isRetry = rule.retryPlannedAt == plannedAt && rule.retryFailedWfIds.isNotEmpty()
        val workflowIds = DispatchPolicy.retryWorkflowSubset(
            rule.resolvedWorkflowIds(), rule.retryFailedWfIds, isRetry
        )
        AppLogger.i("Engine", "execute rule=${ruleId.take(8)} wfs=${workflowIds.size} retry=$isRetry repo=${rule.repoFullName} makeup=$isMakeup")
        val token = tokenStore.getToken()
        if (token == null) {
            AppLogger.e("Engine", "token missing -> retry (user may re-login)")
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = "未登录，token 缺失", errorKey = "未登录，token 缺失", isMakeup = isMakeup)
            retryOrAdvance(rule, plannedAt)
            return TriggerStatus.FAILED
        }

        val waitMinutes = repository.getNetworkWaitMinutesOnce()
        AppLogger.d("Engine", "network check start (timeout=${waitMinutes}min)")
        val networkOk = waitForNetwork(waitMinutes)
        AppLogger.i("Engine", "network ready=$networkOk")
        if (!networkOk) {
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = "等待网络超时（${waitMinutes}分钟）", errorKey = "等待网络超时（{0}分钟）", errorArgs = listOf("$waitMinutes"), isMakeup = isMakeup)
            retryOrAdvance(rule, plannedAt)
            return TriggerStatus.FAILED
        }

        // small delay for network stack / DNS to stabilize
        delay(1_500)

        // parse owner/repo from "owner/repo"
        val parts = rule.repoFullName.split("/")
        if (parts.size != 2) {
            AppLogger.e("Engine", "invalid repo name: ${rule.repoFullName}")
            addLog(rule, plannedAt, TriggerStatus.FAILED, errorMessage = "仓库名无效：${rule.repoFullName}", errorKey = "仓库名无效：{0}", errorArgs = listOf(rule.repoFullName), isMakeup = isMakeup)
            retryOrAdvance(rule, plannedAt)
            return TriggerStatus.FAILED
        }

        // Use the repo's actual default branch (not a hard-coded "main").
        val ref = repository.getSnapshotOnce().repos.firstOrNull { it.fullName == rule.repoFullName }?.defaultBranch?.takeIf { it.isNotBlank() } ?: "main"

        // dispatch every selected workflow (concurrently via coroutines), all must succeed
        val results = kotlinx.coroutines.coroutineScope {
            workflowIds.map { wfId ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    var lastError: String? = null
                    var httpCode: Int? = null
                    var success = false
                    val delays = longArrayOf(1_000, 4_000, 16_000)
                    for (attempt in 0..2) {
                        when (val result = api.dispatchWorkflow(token, parts[0], parts[1], wfId, ref)) {
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

        val failedWfIds = results.filter { !it.second }.map { it.first }
        val allSuccess = results.isNotEmpty() && failedWfIds.isEmpty()
        val lastError = results.firstOrNull { !it.second }?.third
        // Read http code from the FAILED result (first result may be a success 204,
        // mislabeling the failure). On success use 204.
        val httpCode = if (allSuccess) 204 else results.firstOrNull { !it.second }?.third?.toIntOrNull()
        val status = if (allSuccess) {
            if (isMakeup) TriggerStatus.MAKEUP else TriggerStatus.SUCCESS
        } else TriggerStatus.FAILED

        // On retry of a subset, "success" means all retried ones succeeded.
        // Clear retry state so a later reboot does not re-run them.
        val startTracking = allSuccess && parts.size == 2
        val logId = addLog(
            rule = rule,
            plannedAt = plannedAt,
            status = status,
            httpCode = httpCode,
            errorMessage = if (allSuccess) null else "工作流 $failedWfIds 触发失败：$lastError",
            errorKey = if (allSuccess) null else "工作流 {0} 触发失败：{1}",
            errorArgs = if (allSuccess) emptyList() else listOf(failedWfIds.joinToString(), lastError.orEmpty()),
            isMakeup = isMakeup,
            tracking = startTracking
        )

        // Real-duration tracking: mark the log as tracking immediately and let the
        // Worker discover the GitHub run itself on its first poll (~20s later).
        // Previously we queried runs here, but GitHub often hasn't created the run
        // yet right after dispatch, so tracking silently never started and the
        // history card showed no duration.
        // Duration tracking is always on — the history card needs it. The
        // "result notification" switch only controls the notification, not tracking.
        if (startTracking) {
            RunTrackerWorker.enqueue(context, logId, rule.id)
            AppLogger.i("Engine", "tracking started log=${logId.take(8)}")
        }

        // Success advances to the next slot; failure retries ONLY the failed
        // workflows of the SAME slot (never re-sends already-successful ones).
        if (allSuccess) {
            if (advance) advanceRule(rule)
            else {
                // Manual trigger: record only, never consume/reschedule the
                // user's planned slot.
                if (rule.retryAt != null) repository.updateRule(rule.copy(retryAt = null, retryPlannedAt = null, retryFailedWfIds = emptyList()))
            }
        } else retryOrAdvance(rule, plannedAt, failedWfIds)
        return status
    }

    /** Called when execute() throws unexpectedly: log the failure and use the
     *  same persisted retry path so the slot is not silently lost. */
    suspend fun handleException(ruleId: String, plannedAt: Long, isMakeup: Boolean, e: Exception) {
        val rule = repository.getRuleOnce(ruleId) ?: return
        addLog(
            rule, plannedAt, TriggerStatus.FAILED,
            errorMessage = "执行异常：${e.message}", errorKey = "执行异常：{0}",
            errorArgs = listOf(e.message.orEmpty()), isMakeup = isMakeup
        )
        retryOrAdvance(rule, plannedAt, rule.retryFailedWfIds)
    }

    /** Retry a failed slot a few times (short delay, same plannedAt), then give up.
     *  Persists retryAt/failed-wfIds to the Rule so a reboot can restore the retry
     *  alarm — an in-memory AlarmManager alarm alone is lost on reboot/process death,
     *  which used to strand the slot forever (missed check-in). */
    private suspend fun retryOrAdvance(rule: Rule, plannedAt: Long, failedWfIds: List<Long> = emptyList()) {
        val failCount = repository.getLogsOnce().count {
            it.ruleId == rule.id && it.plannedAt == plannedAt && it.status == TriggerStatus.FAILED
        }
        if (!DispatchPolicy.shouldRetry(failCount)) {
            AppLogger.w("Engine", "rule=${rule.id.take(8)} slot=$plannedAt exhausted $failCount retries -> advance")
            advanceRule(rule)
            return
        }
        val retryAt = System.currentTimeMillis() + RETRY_DELAY_MS
        // Persist BEFORE scheduling so a crash between the two still recovers.
        repository.updateRule(rule.copy(
            retryAt = retryAt,
            retryPlannedAt = plannedAt,
            retryFailedWfIds = failedWfIds
        ))
        alarmScheduler.scheduleRetry(rule.id, rule.workflowName, retryAt, plannedAt)
        AppLogger.i("Engine", "rule=${rule.id.take(8)} slot=$plannedAt retry #${failCount + 1} at=$retryAt failedWfs=${failedWfIds.size}")
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
                    lastFiredAt = System.currentTimeMillis(),
                    retryAt = null, retryPlannedAt = null, retryFailedWfIds = emptyList()
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
                lastFiredAt = System.currentTimeMillis(),
                retryAt = null, retryPlannedAt = null, retryFailedWfIds = emptyList()
            )
        )
        scheduleAt(updated, effective)
    }

    /** Schedule at the effective (drifted) time, but keep logs keyed to the base slot. */
    private fun scheduleAt(rule: Rule, effectiveAt: Long) {
        val baseSlot = rule.nextFireAt ?: effectiveAt
        alarmScheduler.scheduleWithSlot(rule, triggerAt = effectiveAt, plannedAt = baseSlot)
    }

    private suspend fun addLog(
        rule: Rule,
        plannedAt: Long,
        status: TriggerStatus,
        httpCode: Int? = null,
        errorMessage: String? = null,
        errorKey: String? = null,
        errorArgs: List<String> = emptyList(),
        isMakeup: Boolean = false,
        tracking: Boolean = false
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
                errorKey = errorKey,
                errorArgs = errorArgs,
                isMakeup = isMakeup,
                durationMs = System.currentTimeMillis() - (repository.runningStartOnce(rule.id) ?: System.currentTimeMillis())
            ),
            tracking = tracking
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
        // VALIDATED means real internet (captive portals / WiFi without internet
        // still report INTERNET but not VALIDATED). Fall back to transport-only
        // on older APIs where VALIDATED may be absent.
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transportOk = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return hasInternet && transportOk && validated
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
