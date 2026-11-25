package com.lucaslu.tickhub.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Single shared haptic feedback: MIUI-style short crisp "tick" everywhere. */
object Haptics {
    @Volatile
    var enabled: Boolean = true

    fun tick(context: Context) {
        if (!enabled) return
        runCatching {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            when {
                // system-defined EFFECT_TICK is the short, crisp "da" click used by MIUI toggles
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    vibrator.vibrate(VibrationEffect.createOneShot(10, 180))
                else -> @Suppress("DEPRECATION") vibrator.vibrate(10)
            }
        }
    }
}
