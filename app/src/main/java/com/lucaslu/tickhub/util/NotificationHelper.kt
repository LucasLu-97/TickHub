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
    private const val CHANNEL_ID = "tickhub_run_result"
    private val CHANNEL_NAME get() = com.lucaslu.tickhub.util.L10n.tNow("触发结果通知")
    private const val NOTIF_ID_BASE = 2000
    // Clock blue from the launcher icon; tints the monochrome small icon.
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
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
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
                .setSmallIcon(R.drawable.ic_notif_small)
                .setColor(ACCENT_COLOR)
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
                .setSmallIcon(R.drawable.ic_notif_small)
                .setColor(ACCENT_COLOR)
                .setContentIntent(contentIntent(context))
                .setContentTitle(L10n.tNow("TickHub · 精确闹钟未开启"))
                .setContentText(L10n.tNow("请在设置开启精确闹钟，否则定时可能延迟数分钟"))
                .setAutoCancel(true)
                .build()
            nm.notify(9999, notif) // fixed id: repeated schedules overwrite, no spam
        }
    }

    /** Holiday-calendar refresh outcome — lets the user know whether the
     *  Dec-17 auto pull or a manual toggle actually fetched data. */
    fun notifyHolidayRefresh(context: Context, success: Boolean, manual: Boolean) {
        runCatching {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val title = if (success) L10n.tNow("TickHub · 节假日数据已更新") else L10n.tNow("TickHub · 节假日数据拉取失败")
            val text = if (success) {
                L10n.tNow(if (manual) "手动刷新成功，当年节假日数据已就绪" else "年度自动刷新成功，已获取次年放假安排")
            } else {
                L10n.tNow("请检查网络后重试；可到设置关闭再开启节假日开关重新拉取")
            }
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_small)
                .setColor(ACCENT_COLOR)
                .setContentIntent(contentIntent(context))
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(if (manual) 9996 else 9997, notif) // fixed ids: overwrite, no spam
        }
    }

    fun notifyResult(context: Context, idSeed: Int, title: String, message: String, success: Boolean) {
        runCatching {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_small)
                .setColor(ACCENT_COLOR)
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
