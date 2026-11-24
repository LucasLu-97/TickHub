package com.lucaslu.tickhub.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lucaslu.tickhub.data.Rule
import com.lucaslu.tickhub.data.RuleStatus

/**
 * Registers precise alarms via AlarmManager.setExactAndAllowWhileIdle.
 * One alarm per enabled rule, identified by requestCode derived from rule id.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(rule: Rule) {
        if (rule.status != RuleStatus.ENABLED) return
        val triggerAt = rule.nextFireAt ?: return
        if (triggerAt <= System.currentTimeMillis()) return

        val pi = pendingIntentFor(rule.id, triggerAt)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    fun cancel(ruleId: String) {
        runCatching { alarmManager.cancel(pendingIntentFor(ruleId, 0L)) }
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
