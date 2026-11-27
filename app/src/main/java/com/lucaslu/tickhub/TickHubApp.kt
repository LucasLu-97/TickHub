package com.lucaslu.tickhub

import android.app.Application
import com.lucaslu.tickhub.util.AppLogger
import com.lucaslu.tickhub.util.L10n
import kotlinx.coroutines.runBlocking

class TickHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        // Load language synchronously at process start so background entries
        // (alarm Worker / receiver / BootReceiver) also get the correct locale —
        // not just when the user opens an Activity.
        runBlocking { L10n.load(this@TickHubApp) }
        AppLogger.init(this)
        AppLogger.i("App", "TickHub started, version=${packageManager.getPackageInfo(packageName, 0).versionName}, lang=${L10n.lang.value.code}")
    }

    /** Persist any uncaught crash to a file BEFORE the process dies, so a
     *  flash-crash can be diagnosed even without logcat access. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val text = "Thread: ${thread.name}\n${sw.toString()}\n"
                java.io.File(filesDir, AppLogger.CRASH_FILE).writeText(text)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
