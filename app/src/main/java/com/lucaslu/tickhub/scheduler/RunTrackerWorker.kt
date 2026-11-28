package com.lucaslu.tickhub.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lucaslu.tickhub.data.GhWorkflowRun
import com.lucaslu.tickhub.data.TokenStore
import com.lucaslu.tickhub.data.TriggerLog
import com.lucaslu.tickhub.network.ApiResult
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.util.AppLogger
import com.lucaslu.tickhub.util.NotificationHelper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Polls GitHub Actions for the real start/end time of dispatched runs.
 * Each poll is a short WorkManager wake-up, NOT a foreground service. Intervals
 * back off 5s -> 15s -> 30s (30s cap). Polling continues until the run reaches
 * "completed", with a ~5-hour safety net (600 polls) against an endless loop.
 * The live duration is persisted every 3rd poll so the history card shows a
 * growing timer (even across process death) without excess DataStore writes.
 */
class RunTrackerWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val logId = inputData.getString(KEY_LOG_ID) ?: return Result.failure()
        val ruleId = inputData.getString(KEY_RULE_ID) ?: return Result.failure()
        val poll = inputData.getInt(KEY_POLL, 0)
        val token = TokenStore(applicationContext).getToken() ?: return Result.failure()
        val api = GitHubApi()
        val repo = AppRepository(applicationContext, api)
        val log: TriggerLog? = repo.getLogsOnce().firstOrNull { it.id == logId }
        val rule = repo.getRuleOnce(ruleId)
        val firedAt = log?.firedAt ?: System.currentTimeMillis()

        // Manual (non-rule) triggers carry their own repo/workflow ids in the log;
        // rule-triggered logs fall back to the rule. This lets "trigger now" from
        // the Repos tab get the same real-duration tracking as scheduled runs.
        val repoFullName = log?.repoFullName?.takeIf { it.isNotBlank() } ?: rule?.repoFullName.orEmpty()
        val workflowIds = log?.trackWorkflowIds?.takeIf { it.isNotEmpty() }
            ?: rule?.resolvedWorkflowIds().orEmpty()
        val workflowNames = log?.workflowNames?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(rule?.workflowName?.takeIf { it.isNotBlank() })

        val parts = repoFullName.split("/")
        if (parts.size != 2 || workflowIds.isEmpty()) return Result.failure()

        // For each workflow: use its known runId if we have one, but ALWAYS fall
        // back to discovery when the id is missing or the run vanished (404).
        // This fixes two bugs: late-created runs of a multi-workflow rule were
        // never found, and a deleted run (404) used to stall until the cap.
        val allRuns = mutableListOf<Pair<Long, GhWorkflowRun>>()
        val knownRunIds = log?.runIds.orEmpty().toMutableMap()
        for (wfId in workflowIds) {
            val knownId = knownRunIds[wfId.toString()]
            var found: GhWorkflowRun? = null
            // Only discover when we have NO id, or a 404/410 proved the old run
            // vanished. A transient network/401/5xx error must NOT fall through
            // to discovery: `listWorkflowRuns` may return a DIFFERENT run and we
            // would persist that wrong id, misreporting duration/conclusion.
            var allowDiscovery = knownId == null
            if (knownId != null) {
                when (val result = api.getWorkflowRun(token, parts[0], parts[1], knownId)) {
                    is ApiResult.Success -> found = result.data
                    is ApiResult.Error -> {
                        if (result.code == 404 || result.code == 410) {
                            knownRunIds.remove(wfId.toString())
                            allowDiscovery = true // confirmed gone — find the replacement
                        }
                        // network / 401 / 5xx: keep id, retry next poll; NO discovery
                    }
                }
            }
            if (found == null && allowDiscovery) {
                when (val result = api.listWorkflowRuns(token, parts[0], parts[1], wfId, 5)) {
                    is ApiResult.Success -> {
                        found = result.data.workflowRuns
                            .filter { runCreatedAfter(it, firedAt - OLD_RUN_TOLERANCE_MS) }
                            .maxByOrNull { parseInstant(it.createdAt)?.toEpochMilli() ?: 0L }
                    }
                    is ApiResult.Error -> Unit
                }
            }
            if (found != null) {
                allRuns.add(wfId to found)
                knownRunIds[wfId.toString()] = found.id
            }
        }
        // Persist the (possibly grown) run-id map for precise lookups next round.
        // Compare CONTENT, not size: a 404'd id replaced by a new one keeps the
        // same size but must be persisted, else next poll rediscovers forever.
        if (knownRunIds.isNotEmpty() && knownRunIds != log?.runIds?.toMap()) {
            repo.updateLogTracking(logId, knownRunIds)
        }

        // No run created yet (GitHub lag): keep the timer growing from firedAt and retry.
        if (allRuns.isEmpty()) {
            persistLiveElapsed(repo, logId, firedAt, null, poll)
            return continueOrGiveUp(logId, ruleId, poll, repo, log)
        }

        val allDone = allRuns.size == workflowIds.size && allRuns.all { it.second.status == "completed" }
        if (!allDone) {
            // Run exists but still running (or some workflows not found yet): keep the timer.
            persistLiveElapsed(repo, logId, firedAt, allRuns.mapNotNull { parseInstant(it.second.runStartedAt.ifBlank { it.second.createdAt })?.toEpochMilli() }.minOrNull(), poll)
            return continueOrGiveUp(logId, ruleId, poll, repo, log)
        }

        // Completed: compute real duration from earliest run_started_at to latest updated_at.
        val starts = allRuns.mapNotNull { parseInstant(it.second.runStartedAt.ifBlank { it.second.createdAt }) }
        val ends = allRuns.mapNotNull { parseInstant(it.second.updatedAt) }
        val durationMs = if (starts.isNotEmpty() && ends.isNotEmpty()) {
            Duration.between(starts.min(), ends.max()).toMillis().coerceAtLeast(0L)
        } else (System.currentTimeMillis() - firedAt).coerceAtLeast(0L)
        // Three-way verdict (pure logic, JVM-tested in DispatchPolicyTest).
        val verdict = DispatchPolicy.runVerdict(allRuns.map { it.second.conclusion })
        val success = verdict.success
        val cancelled = verdict.cancelled
        repo.finalizeLog(logId, durationMs, success, cancelled)

        if (repo.isRunTrackingEnabledOnce() && !cancelled) {
            val statusText = if (success) com.lucaslu.tickhub.util.L10n.tNow("执行成功") else com.lucaslu.tickhub.util.L10n.tNow("执行失败")
            NotificationHelper.notifyResult(
                applicationContext,
                logId.hashCode(),
                "TickHub · $statusText",
                "${workflowNames.firstOrNull().orEmpty()}" + com.lucaslu.tickhub.util.L10n.tNow(" 用时 ") + "${durationMs / 1000}s",
                success
            )
        }
        AppLogger.i("Tracker", "finalized log=${logId.take(8)} duration=${durationMs}ms success=$success runs=${allRuns.size} polls=$poll")
        return Result.success()
    }

    /** Persist a live-growing duration so the history card always shows a timer. */
    private suspend fun persistLiveElapsed(repo: AppRepository, logId: String, firedAt: Long, runStartMs: Long?, poll: Int) {
        // GitHub completion detection polls every 5s/15s/30s, but the live timer
        // is written to DataStore only every 3rd poll — each write re-encodes
        // the whole logs JSON, so this cuts background IO/CPU by ~2/3.
        if (poll % 3 != 0 && poll != MAX_POLLS) return
        val base = runStartMs ?: firedAt
        val elapsed = (System.currentTimeMillis() - base).coerceAtLeast(0L)
        repo.updateLogElapsed(logId, elapsed)
    }

    private suspend fun continueOrGiveUp(
        logId: String, ruleId: String, poll: Int, repo: AppRepository, log: TriggerLog?
    ): Result {
        if (poll < MAX_POLLS) {
            reenqueue(logId, ruleId, poll + 1)
            return Result.success()
        }
        // Safety net (~5h): keep elapsed time, mark as unconfirmed (not failed).
        val elapsed = (System.currentTimeMillis() - (log?.firedAt ?: System.currentTimeMillis())).coerceAtLeast(0L)
        repo.finalizeUnconfirmed(logId, elapsed)
        if (repo.isRunTrackingEnabledOnce()) {
            NotificationHelper.notifyResult(
                applicationContext,
                logId.hashCode(),
                com.lucaslu.tickhub.util.L10n.tNow("TickHub · 执行结果未确认"),
                com.lucaslu.tickhub.util.L10n.tNow("无法从 GitHub 获取执行结果，请到仓库页查看"),
                false
            )
        }
        return Result.success()
    }

    /** Accept a run only if it was created at/after the dispatch (minus tolerance). */
    private fun runCreatedAfter(run: GhWorkflowRun, thresholdMs: Long): Boolean {
        val created = parseInstant(run.createdAt)?.toEpochMilli() ?: return false
        return created >= thresholdMs
    }

    private fun parseInstant(value: String): Instant? = try {
        if (value.isBlank()) null else Instant.parse(value)
    } catch (e: Exception) { null }

    private fun reenqueue(logId: String, ruleId: String, poll: Int) {
        val data = workDataOf(KEY_LOG_ID to logId, KEY_RULE_ID to ruleId, KEY_POLL to poll)
        // Stepped backoff: 5s for the first minute (catch fast runs promptly),
        // then 15s, then a 30s cap. Cuts API calls/battery for long
        // runs without delaying short ones.
        val delaySec = when {
            poll < 12 -> POLL_DELAY_SEC          // <1 min: 5s
            poll < 40 -> 15L                     // ~1-10 min: 15s
            else -> 30L                          // long runs: 30s cap
        }
        val req = OneTimeWorkRequestBuilder<RunTrackerWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setInputData(data)
            .addTag("track-$logId")
            .addTag("tickhub_track_all")
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("$UNIQUE_NAME-$logId", ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        const val KEY_LOG_ID = "log_id"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_POLL = "poll"
        const val UNIQUE_NAME = "tickhub_track"
        const val POLL_DELAY_SEC = 5L
        // Safety net ~4h50m with 5/15/30s backoff; normal runs finish in minutes.
        // 12*5s + 28*15s + 560*30s ~= 4h48m (+20s initial delay).
        const val MAX_POLLS = 600
        const val OLD_RUN_TOLERANCE_MS = 2 * 60 * 1000L

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("tickhub_track_all")
        }

        fun enqueue(context: Context, logId: String, ruleId: String) {
            val data = workDataOf(KEY_LOG_ID to logId, KEY_RULE_ID to ruleId, KEY_POLL to 0)
            val req = OneTimeWorkRequestBuilder<RunTrackerWorker>()
                .setInitialDelay(20, TimeUnit.SECONDS) // let GitHub create the run first
                .setInputData(data)
                .addTag("track-$logId")
            .addTag("tickhub_track_all")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$UNIQUE_NAME-$logId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
