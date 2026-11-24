package com.lucaslu.tickhub.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lucaslu.tickhub.data.RuleStatus
import com.lucaslu.tickhub.network.GitHubApi
import com.lucaslu.tickhub.repository.AppRepository
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
                repository.getRulesOnce()
                    .filter { it.status == RuleStatus.ENABLED }
                    .forEach { scheduler.schedule(it) }
            } catch (e: Exception) {
                // best effort
            } finally {
                pending.finish()
            }
        }
    }
}
