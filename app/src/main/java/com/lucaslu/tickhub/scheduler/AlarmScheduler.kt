package com.lucaslu.tickhub.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registers precise alarms via AlarmManager.setExactAndAllowWhileIdle.
 * One alarm per enabled rule, identified by requestCode derived from rule id.
 * On Android 12+ without exact-alarm permission, falls back to a non-exact
 * alarm (still fires) and notifies the user instead of silently failing.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(rule: Rule) {
        if (rule.status != RuleStatus.ENABLED) return
        val triggerAt = rule.nextFireAt ?: return
        scheduleAt(rule.id, rule.workflowName, triggerAt, triggerAt)
    }

    /** Schedule with distinct wake time (drifted) and logical slot time (base).
     *  Logs/dedup always key on [plannedAt]; only the alarm fires at [triggerAt]. */
    fun scheduleWithSlot(rule: Rule, triggerAt: Long, plannedAt: Long) {
        if (rule.status != RuleStatus.ENABLED) return
        scheduleAt(rule.id, rule.workflowName, triggerAt, plannedAt)
    }

    /** Retry alarm for a failed slot: fires at [triggerAt] but keeps the original
     *  [plannedAt] so logs/dedup stay keyed to the real scheduled slot. */
    fun scheduleRetry(ruleId: String, workflowName: String, triggerAt: Long, plannedAt: Long) {
        scheduleAt(ruleId, workflowName, triggerAt, plannedAt)
    }

    private fun scheduleAt(ruleId: String, workflowName: String, triggerAt: Long, plannedAt: Long) {
        if (triggerAt <= System.currentTimeMillis()) return
        val pi = pendingIntentFor(ruleId, plannedAt)
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(triggerAt))
        val canExact = canScheduleExactAlarms()
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                "exact"
            } else {
                // No exact-alarm permission (Android 12+): still fire, just not exact.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                "inexact"
            }
        }
        val mode = result.getOrDefault("failed")
        AppLogger.i("Alarm", "schedule rule=${ruleId.take(8)} wf=$workflowName at=$timeStr mode=$mode canExact=$canExact")
        if (mode == "inexact" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // One-time heads-up: exact alarms are off; triggers may be delayed.
            com.lucaslu.tickhub.util.NotificationHelper.notifyExactAlarmNeeded(context)
        }
    }

    fun cancel(ruleId: String) {
        runCatching { alarmManager.cancel(pendingIntentFor(ruleId, 0L)) }
        AppLogger.i("Alarm", "cancel rule=${ruleId.take(8)}")
    }

    fun cancelAll(ruleIds: List<String>) = ruleIds.forEach { cancel(it) }

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    private fun pendingIntentFor(ruleId: String, plannedAt: Long): PendingIntent {
        val intent = Intent(context, DispatchReceiver::class.java).apply {
            action = ACTION_DISPATCH
            putExtra(EXTRA_RULE_ID, ruleId)
            putExtra(DispatchReceiver.KEY_PLANNED_AT, plannedAt)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            ruleId.hashCode(),
            intent,
            flags
        )
    }

    companion object {
        const val ACTION_DISPATCH = "com.lucaslu.tickhub.action.DISPATCH"
        const val EXTRA_RULE_ID = "rule_id"
    }
}
