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
import com.lucaslu.tickhub.util.AppLogger

/**
 * WorkManager worker that performs the actual dispatch in the background,
 * surviving receiver lifecycle limits.
 */
class DispatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE_ID)
        if (ruleId == null) {
            AppLogger.e("Worker", "no ruleId in input")
            return Result.failure()
        }
        val plannedAt = inputData.getLong(KEY_PLANNED_AT, System.currentTimeMillis())
        val isMakeup = inputData.getBoolean(KEY_IS_MAKEUP, false)
        AppLogger.i("Worker", "START rule=${ruleId.take(8)} makeup=$isMakeup attempt=$runAttemptCount")

        val engine = DispatchEngine(applicationContext)
        val wakeLock = engine.acquireWakeLock("dispatch-$ruleId")
        val startAt = System.currentTimeMillis()
        engine.markRunning(ruleId, startAt)
        return try {
            val status = engine.execute(ruleId, isMakeup, plannedAt)
            AppLogger.i("Worker", "DONE rule=${ruleId.take(8)} status=$status")
            Result.success()
        } catch (e: Exception) {
            AppLogger.e("Worker", "EXCEPTION rule=${ruleId.take(8)}: ${e.message}", e)
            Result.failure()
        } finally {
            engine.markDone(ruleId)
            wakeLock?.let { if (it.isHeld) it.release() }
            AppLogger.d("Worker", "wakeLock released rule=${ruleId.take(8)}")
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
