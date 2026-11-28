package com.lucaslu.tickhub.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lucaslu.tickhub.R

/** Posts a simple completion notification for tracked workflow runs. */
object NotificationHelper {
    // v3 channel: HIGH importance makes HyperOS show the full-colour app icon in
    // the notification shade (DEFAULT renders the tinted small-icon instead).
    // New id also busts the cached channel created by earlier builds.
    private const val CHANNEL_ID = "tickhub_run_result_v3"
    private val CHANNEL_NAME get() = com.lucaslu.tickhub.util.L10n.tNow("触发结果通知")
    private const val NOTIF_ID_BASE = 2000
    // Clock blue from the final launcher icon (#58A6FF) — notification small
    // icon uses the same clock silhouette, tinted with this accent on MIUI.
    private const val ACCENT_COLOR = 0xFF58A6FF.toInt()

    /** Tap a notification to return to the app (with a clean back stack). */
    private fun contentIntent(context: Context): android.app.PendingIntent {
        val intent = Intent(context, com.lucaslu.tickhub.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
        return android.app.PendingIntent.getActivity(context, 0, intent, flags)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = com.lucaslu.tickhub.util.L10n.tNow("工作流触发执行完成后的成功/失败通知")
                    }
                )
            }
        }
    }

    /** Token revoked (401) — tell the user to sign in again; tapping opens the app. */
    fun notifyAuthExpired(context: Context) {
        runCatching {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_v4)
                // VERIFY v2.2.5: setColor removed — does the blue shade disappear?
                .setContentIntent(contentIntent(context))
                .setContentTitle(L10n.tNow("TickHub · 登录已失效"))
                .setContentText(L10n.tNow("请重新登录以恢复定时触发"))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(9998, notif)
        }
    }

    /** One-time heads-up when exact-alarm permission is missing (triggers may be delayed). */
    fun notifyExactAlarmNeeded(context: Context) {
        runCatching {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_v4)
                // VERIFY v2.2.5: setColor removed — does the blue shade disappear?
                .setContentIntent(contentIntent(context))
                .setContentTitle(L10n.tNow("TickHub · 精确闹钟未开启"))
                .setContentText(L10n.tNow("请在设置开启精确闹钟，否则定时可能延迟数分钟"))
                .setAutoCancel(true)
                .build()
            nm.notify(9999, notif) // fixed id: repeated schedules overwrite, no spam
        }
    }

    fun notifyResult(context: Context, idSeed: Int, title: String, message: String, success: Boolean) {
        runCatching {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_v4)
                // VERIFY v2.2.5: setColor removed — does the blue shade disappear?
                .setContentIntent(contentIntent(context))
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setPriority(if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
                .build()
            val safeSeed = (idSeed and 0x7fffffff) % 1_000_000 // avoid overflow past base
            nm.notify(NOTIF_ID_BASE + safeSeed, notif)
        }
    }
}
