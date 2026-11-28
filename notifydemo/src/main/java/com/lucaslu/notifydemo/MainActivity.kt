package com.lucaslu.notifydemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity

/**
 * Minimal notification-icon test harness. Each button posts a notification
 * with a different icon configuration so HyperOS rendering can be compared.
 * Separate package (com.lucaslu.notifydemo) — does not touch TickHub.
 */
class MainActivity : Activity() {

    private val channelId = "demo"
    private var counter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "通知图标对比测试\n下拉通知栏观察每个通知左侧图标"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        })

        val cases = listOf(
            "A · 当前TickHub: small时钟 + 蓝色(无large)" to 0,
            "B · small时钟 + 桌面大图标 + 蓝色" to 1,
            "C · small时钟 + 桌面大图标(无着色)" to 2,
            "D · small时钟 + 深色着色(无large)" to 3,
            "E · 桌面图标当small(彩色无效,看形状)" to 4,
            "F · 清空全部测试通知" to 5
        )
        cases.forEach { (label, id) ->
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onCase(id) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
            })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Demo", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }

    private fun onCase(which: Int) {
        if (which == 5) {
            getSystemService(NotificationManager::class.java).cancelAll()
            return
        }
        counter++
        val smallClock = R.drawable.ic_notif_small
        val smallLauncher = R.mipmap.ic_launcher_flat
        when (which) {
            0 -> post("A", "small=时钟, color=蓝, large=无", smallClock, null, 0xFF58A6FF.toInt())
            1 -> post("B", "small=时钟, color=蓝, large=桌面图标", smallClock, smallLauncher, 0xFF58A6FF.toInt())
            2 -> post("C", "small=时钟, color=无, large=桌面图标", smallClock, smallLauncher, null)
            3 -> post("D", "small=时钟, color=深色, large=无", smallClock, null, 0xFF0D1117.toInt())
            4 -> post("E", "small=桌面图标(当单色剪影用)", smallLauncher, null, 0xFF58A6FF.toInt())
        }
    }

    private fun post(tag: String, desc: String, smallRes: Int, largeRes: Int?, color: Int?) {
        val b = Notification.Builder(this, channelId)
            .setSmallIcon(smallRes)
            .setContentTitle("$tag · $desc")
            .setContentText("下拉看左侧图标 · #$counter")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        if (largeRes != null) {
            b.setLargeIcon(BitmapFactory.decodeResource(resources, largeRes))
        }
        if (color != null) b.setColor(color)
        getSystemService(NotificationManager::class.java).notify(counter, b.build())
    }
}
