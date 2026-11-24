package com.lucaslu.tickhub.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lucaslu.tickhub.data.TriggerStatus

/**
 * WorkManager worker that performs the actual dispatch in the background,
 * surviving receiver lifecycle limits. Uses expedited work for timely execution.
 */
class DispatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE_ID) ?: return Result.failure()
        val plannedAt = inputData.getLong(KEY_PLANNED_AT, System.currentTimeMillis())
        val isMakeup = inputData.getBoolean(KEY_IS_MAKEUP, false)

        val engine = DispatchEngine(applicationContext)
        val wakeLock = engine.acquireWakeLock("dispatch-$ruleId")
        return try {
            val status = engine.execute(ruleId, isMakeup, plannedAt)
            if (status == TriggerStatus.FAILED) {
                // Don't retry here: DispatchEngine already retried x3 and logged.
                Result.success()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Result.failure()
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
        const val KEY_PLANNED_AT = "planned_at"
        const val KEY_IS_MAKEUP = "is_makeup"
        const val UNIQUE_NAME = "tickhub_dispatch"

        fun enqueue(context: Context, ruleId: String, plannedAt: Long, isMakeup: Boolean) {
            val data = workDataOf(
                KEY_RULE_ID to ruleId,
                KEY_PLANNED_AT to plannedAt,
                KEY_IS_MAKEUP to isMakeup
            )
            val request = OneTimeWorkRequestBuilder<DispatchWorker>()
                .setInputData(data)
                .addTag("dispatch-$ruleId")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("${UNIQUE_NAME}-$ruleId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
