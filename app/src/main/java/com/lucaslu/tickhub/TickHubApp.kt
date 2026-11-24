package com.lucaslu.tickhub

import android.app.Application
import com.lucaslu.tickhub.util.AppLogger

class TickHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i("App", "TickHub started, version=${packageManager.getPackageInfo(packageName, 0).versionName}")
    }
}
