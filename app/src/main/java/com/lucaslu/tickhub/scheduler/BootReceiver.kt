package com.lucaslu.tickhub.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-registers all enabled rule alarms after device boot.
 * Full implementation is M3 of the roadmap.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TODO(M3): read enabled rules from Room and re-register AlarmManager alarms.
    }
}
