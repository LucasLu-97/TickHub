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
 * Each poll is a short WorkManager wake-up (5s delay), NOT a foreground service.
 * Polling continues until the run actually reaches "completed" — there is no
 * short fixed cap, only a very long safety net (24h) to avoid a truly endless loop.
 * The live duration is persisted on every poll so the history card always shows
 * a growing timer, even across process death.
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

        val allRuns = mutableListOf<Pair<Long, GhWorkflowRun>>()
        var anyError = false
        val knownRunIds = log?.runIds.orEmpty()
        if (knownRunIds.isNotEmpty()) {
            // Precise mode: we already discovered the exact run ids for this dispatch.
            for ((wfId, runId) in knownRunIds) {
                when (val result = api.getWorkflowRun(token, parts[0], parts[1], runId)) {
                    is ApiResult.Success -> allRuns.add(wfId to result.data)
                    is ApiResult.Error -> anyError = true
                }
            }
        } else {
            // Discovery mode: find the newest run created around this dispatch and
            // remember its id on the log so all later polls use precise lookup.
            for (wfId in workflowIds) {
                when (val result = api.listWorkflowRuns(token, parts[0], parts[1], wfId, 5)) {
                    is ApiResult.Success -> {
                        val newest = result.data.workflowRuns
                            .filter { runCreatedAfter(it, firedAt - OLD_RUN_TOLERANCE_MS) }
                            .maxByOrNull { parseInstant(it.createdAt)?.toEpochMilli() ?: 0L }
                        if (newest != null) allRuns.add(wfId to newest) else anyError = true
                    }
                    is ApiResult.Error -> anyError = true
                }
            }
            if (allRuns.isNotEmpty()) {
                repo.updateLogTracking(logId, allRuns.associate { (wfId, run) -> wfId.toString() to run.id })
            }
        }

        // No run created yet (GitHub lag): keep the timer growing from firedAt and retry.
        if (allRuns.isEmpty()) {
            persistLiveElapsed(repo, logId, firedAt, null)
            return continueOrGiveUp(logId, ruleId, poll, repo, log)
        }

        val allDone = allRuns.all { it.second.status == "completed" }
        if (!allDone) {
            // Run exists but still running: persist a live duration from its start.
            persistLiveElapsed(repo, logId, firedAt, allRuns.mapNotNull { parseInstant(it.second.runStartedAt.ifBlank { it.second.createdAt })?.toEpochMilli() }.minOrNull())
            return continueOrGiveUp(logId, ruleId, poll, repo, log)
        }

        // Completed: compute real duration from earliest run_started_at to latest updated_at.
        val starts = allRuns.mapNotNull { parseInstant(it.second.runStartedAt.ifBlank { it.second.createdAt }) }
        val ends = allRuns.mapNotNull { parseInstant(it.second.updatedAt) }
        val durationMs = if (starts.isNotEmpty() && ends.isNotEmpty()) {
            Duration.between(starts.min(), ends.max()).toMillis().coerceAtLeast(0L)
        } else (System.currentTimeMillis() - firedAt).coerceAtLeast(0L)
        val success = allRuns.all { it.second.conclusion == "success" } && !anyError
        repo.finalizeLog(logId, durationMs, success)

        if (repo.isRunTrackingEnabledOnce()) {
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
    private suspend fun persistLiveElapsed(repo: AppRepository, logId: String, firedAt: Long, runStartMs: Long?) {
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
        // Extreme safety net (24h): keep the elapsed time, mark as unconfirmed (not failed).
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
        val req = OneTimeWorkRequestBuilder<RunTrackerWorker>()
            .setInitialDelay(POLL_DELAY_SEC, TimeUnit.SECONDS)
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
        // Safety net only: 3h at 5s (a normal run finishes far sooner).
        const val MAX_POLLS = 2160
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
