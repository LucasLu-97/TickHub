package com.lucaslu.tickhub.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/** Posts a simple completion notification for tracked workflow runs. */
object NotificationHelper {
    private const val CHANNEL_ID = "tickhub_run_result"
    private val CHANNEL_NAME get() = com.lucaslu.tickhub.util.L10n.tNow("触发结果通知")
    private const val NOTIF_ID_BASE = 2000

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

    fun notifyResult(context: Context, idSeed: Int, title: String, message: String, success: Boolean) {
        runCatching {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setPriority(if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIF_ID_BASE + (idSeed and 0x7fffffff), notif)
        }
    }
}
