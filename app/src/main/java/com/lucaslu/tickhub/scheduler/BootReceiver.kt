package com.lucaslu.tickhub.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.repository.AppRepository
import com.lucaslu.tickhub.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-registers all enabled rule alarms after device boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.LOCKED_BOOT_COMPLETED") return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AppRepository(context.applicationContext, GitHubApi())
                val scheduler = AlarmScheduler(context.applicationContext)
                val engine = DispatchEngine(context.applicationContext)
                val enabled = repository.getRulesOnce().filter { it.status == RuleStatus.ENABLED }
                AppLogger.i("Boot", "device rebooted, re-scheduling ${enabled.size} enabled rules")
                val now = System.currentTimeMillis()
                enabled.forEach { rule ->
                    val base = rule.nextFireAt ?: return@forEach
                    // Restore a pending retry alarm (lost on reboot) if its wake
                    // time is still in the future; catchUp handles overdue ones.
                    if (rule.retryAt != null && rule.retryPlannedAt == base && rule.retryAt > now) {
                        scheduler.scheduleRetry(rule.id, rule.workflowName, rule.retryAt, base)
                        AppLogger.i("Boot", "restored retry rule=${rule.id.take(8)} at=${rule.retryAt}")
                        return@forEach
                    }
                    val trigger = rule.effectiveFireAt ?: base
                    scheduler.scheduleWithSlot(rule, triggerAt = trigger, plannedAt = base)
                }
                // Recover slots missed while the device was off — without waiting
                // for the user to open the app (core promise of scheduled check-ins).
                val madeUp = engine.catchUpMissed()
                AppLogger.i("Boot", "catchUp after reboot: $madeUp makeup dispatched")
            } catch (e: Exception) {
                AppLogger.e("Boot", "re-schedule failed: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }
}
