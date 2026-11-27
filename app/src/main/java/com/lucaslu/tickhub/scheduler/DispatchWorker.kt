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
        val advance = inputData.getBoolean(KEY_ADVANCE, true)
        AppLogger.i("Worker", "START rule=${ruleId.take(8)} makeup=$isMakeup attempt=$runAttemptCount")

        val engine = DispatchEngine(applicationContext)
        val wakeLock = engine.acquireWakeLock("dispatch-$ruleId")
        return try {
            engine.markRunning(ruleId, System.currentTimeMillis()) // inside try: no wake-lock leak if it throws
            val status = engine.execute(ruleId, isMakeup, plannedAt, advance)
            AppLogger.i("Worker", "DONE rule=${ruleId.take(8)} status=$status")
            Result.success()
        } catch (e: Exception) {
            AppLogger.e("Worker", "EXCEPTION rule=${ruleId.take(8)}: ${e.message}", e)
            // An unexpected exception (e.g. DataStore write failure) must not
            // silently drop the slot: record a FAILED log and schedule the same
            // persisted retry path a normal dispatch failure uses.
            runCatching { engine.handleException(ruleId, plannedAt, isMakeup, advance, e) }
                .onFailure { AppLogger.e("Worker", "handleException FAILED: ${it.message}", it) }
            Result.success() // our own retry owns recovery; avoid WorkManager re-run + duplicate logs
        } finally {
            // Release the wake lock FIRST — markDone does a DataStore write and
            // can throw on cancellation; the old order leaked the lock for 15min.
            wakeLock?.let { if (it.isHeld) it.release() }
            AppLogger.d("Worker", "wakeLock released rule=${ruleId.take(8)}")
            runCatching { engine.markDone(ruleId) }
                .onFailure { AppLogger.e("Worker", "markDone FAILED: ${it.message}", it) }
        }
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
        const val KEY_PLANNED_AT = "planned_at"
        const val KEY_IS_MAKEUP = "is_makeup"
        const val KEY_ADVANCE = "advance" // false for manual trigger: don't consume the planned slot
        const val UNIQUE_NAME = "tickhub_dispatch"

        fun enqueue(context: Context, ruleId: String, plannedAt: Long, isMakeup: Boolean, advance: Boolean = true) {
            val data = workDataOf(
                KEY_RULE_ID to ruleId,
                KEY_PLANNED_AT to plannedAt,
                KEY_IS_MAKEUP to isMakeup,
                KEY_ADVANCE to advance
            )
            val request = OneTimeWorkRequestBuilder<DispatchWorker>()
                .setInputData(data)
                .addTag("dispatch-$ruleId")
                .addTag("tickhub_dispatch_all") // logout cancels ALL queued dispatch work
                .apply { if (!advance) addTag("manual") }
                .build()
            // Unique name includes plannedAt so a manual trigger (now) never
            // replaces a queued scheduled trigger for a different slot.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("${UNIQUE_NAME}-$ruleId-$plannedAt", ExistingWorkPolicy.KEEP, request)
        }
    }
}
