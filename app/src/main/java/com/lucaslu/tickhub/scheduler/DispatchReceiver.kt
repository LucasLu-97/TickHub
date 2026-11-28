package com.lucaslu.tickhub.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lucaslu.tickhub.util.AppLogger

/**
 * Receives precise alarms from AlarmManager and enqueues the actual dispatch work
 * into WorkManager (so the work survives the broadcast lifecycle).
 */
class DispatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_DISPATCH) return
        val ruleId = intent.getStringExtra(AlarmScheduler.EXTRA_RULE_ID)
        if (ruleId == null) {
            AppLogger.w("Receiver", "alarm received without ruleId")
            return
        }
        val plannedAt = intent.getLongExtra(KEY_PLANNED_AT, System.currentTimeMillis())
        val lagSec = (System.currentTimeMillis() - plannedAt) / 1000
        AppLogger.i("Receiver", "alarm FIRED rule=${ruleId.take(8)} lag=${lagSec}s -> enqueue worker")
        // A retry alarm for an overdue slot must keep isMakeup=true: on success
        // it advances from THAT slot so the serial catch-up chain continues,
        // instead of skipping all intermediate overdue slots to "now".
        val isRetry = intent.getBooleanExtra(KEY_IS_RETRY, false)
        val fromOverdue = isRetry && plannedAt < System.currentTimeMillis() - 60_000L
        DispatchWorker.enqueue(context, ruleId, plannedAt, isMakeup = fromOverdue)
    }

    companion object {
        const val KEY_PLANNED_AT = "planned_at"
        const val KEY_IS_RETRY = "is_retry"
    }
}
