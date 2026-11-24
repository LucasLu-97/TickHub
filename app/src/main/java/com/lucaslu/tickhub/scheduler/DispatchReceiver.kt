package com.lucaslu.tickhub.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives precise alarms from AlarmManager and enqueues the actual dispatch work.
 * Full implementation (WakeLock + network wait + dispatch) is M3 of the roadmap.
 */
class DispatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TODO(M3): acquire PARTIAL_WAKE_LOCK, wait for network, POST /dispatches, log result.
    }
}
