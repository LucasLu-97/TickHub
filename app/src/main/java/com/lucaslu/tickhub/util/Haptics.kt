package com.lucaslu.tickhub.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short, crisp haptic tick for deterministic interactions (toggles, checks, wheel snaps). */
object Haptics {
    @Volatile
    var enabled: Boolean = true

    // sharp click: very short, strong onset then instant release
    private val TICK = longArrayOf(0, 9)
    private val AMP = intArrayOf(0, 200)

    fun tick(context: Context) {
        if (!enabled) return
        runCatching {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(TICK, AMP, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(TICK[1])
            }
        }
    }
}
