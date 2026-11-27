package com.lucaslu.tickhub

import android.app.Application
import com.lucaslu.tickhub.util.AppLogger
import com.lucaslu.tickhub.util.L10n
import kotlinx.coroutines.runBlocking

class TickHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load language synchronously at process start so background entries
        // (alarm Worker / receiver / BootReceiver) also get the correct locale —
        // not just when the user opens an Activity.
        runBlocking { L10n.load(this@TickHubApp) }
        AppLogger.init(this)
        AppLogger.i("App", "TickHub started, version=${packageManager.getPackageInfo(packageName, 0).versionName}, lang=${L10n.lang.value.code}")
    }
}
