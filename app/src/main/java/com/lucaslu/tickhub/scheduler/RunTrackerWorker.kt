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
 * Each poll is a short WorkManager wake-up (5s delay between), NOT a persistent
 * foreground service — so it stays lightweight. The loop stops as soon as the
 * run completes (typical 1-3 min = ~12-36 polls), so the 12-min MAX_POLLS is only
 * a worst-case cap for long/stuck runs. 5s gives near-real-time result notification.
 */
class RunTrackerWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val logId = inputData.getString(KEY_LOG_ID) ?: return Result.failure()
        val ruleId = inputData.getString(KEY_RULE_ID) ?: return Result.failure()
        val poll = inputData.getInt(KEY_POLL, 0)
        val token = TokenStore(applicationContext).getToken() ?: return Result.failure()
        val api = GitHubApi()
        val repo = AppRepository(applicationContext, api)
        val rule = repo.getRuleOnce(ruleId)
        if (rule == null) return Result.failure()

        val parts = rule.repoFullName.split("/")
        if (parts.size != 2) return Result.failure()
        val workflowIds = rule.resolvedWorkflowIds()

        val allRuns = mutableListOf<Pair<Long, GhWorkflowRun>>()
        var anyError = false
        for (wfId in workflowIds) {
            when (val result = api.listWorkflowRuns(token, parts[0], parts[1], wfId, 5)) {
                is ApiResult.Success -> {
                    // newest run for this workflow (usually the one we just dispatched)
                    val run = result.data.workflowRuns.firstOrNull()
                    if (run != null) allRuns.add(wfId to run) else anyError = true
                }
                is ApiResult.Error -> anyError = true
            }
        }

        if (allRuns.isEmpty()) {
            return if (poll < MAX_POLLS) { reenqueue(logId, ruleId, poll + 1); Result.success() }
            else { giveUp(repo, logId); Result.success() }
        }

        val allDone = allRuns.all { it.second.status == "completed" }
        if (!allDone && poll < MAX_POLLS) {
            reenqueue(logId, ruleId, poll + 1)
            return Result.success()
        }

        // compute real duration: from earliest run_started_at to latest updated_at
        val starts = allRuns.mapNotNull { parseInstant(it.second.runStartedAt.ifBlank { it.second.createdAt }) }
        val ends = allRuns.mapNotNull { parseInstant(it.second.updatedAt) }
        val durationMs: Long = if (starts.isNotEmpty() && ends.isNotEmpty()) {
            Duration.between(starts.min(), ends.max()).toMillis().coerceAtLeast(0L)
        } else 0L
        val success = allRuns.all { it.second.conclusion == "success" } && !anyError
        repo.finalizeLog(logId, durationMs, success)

        val statusText = if (success) "执行成功" else "执行失败"
        NotificationHelper.notifyResult(
            applicationContext,
            logId.hashCode(),
            "TickHub · $statusText",
            "${rule.workflowNames.firstOrNull() ?: rule.workflowName} 用时 ${durationMs / 1000}s",
            success
        )
        AppLogger.i("Tracker", "finalized log=${logId.take(8)} duration=${durationMs}ms success=$success runs=${allRuns.size}")
        return Result.success()
    }

    private suspend fun giveUp(repo: AppRepository, logId: String) {
        repo.finalizeLog(logId, 0L, false)
        NotificationHelper.notifyResult(applicationContext, logId.hashCode(), "TickHub · 执行结果未确认", "无法从 GitHub 获取执行结果，请到仓库页查看", false)
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
        const val MAX_POLLS = 144 // ~12 minutes at 5s intervals; stops early once the run completes

        fun enqueue(context: Context, logId: String, ruleId: String) {
            val data = workDataOf(KEY_LOG_ID to logId, KEY_RULE_ID to ruleId, KEY_POLL to 0)
            val req = OneTimeWorkRequestBuilder<RunTrackerWorker>()
                .setInitialDelay(20, TimeUnit.SECONDS) // let GitHub create the run first
                .setInputData(data)
                .addTag("track-$logId")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$UNIQUE_NAME-$logId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
