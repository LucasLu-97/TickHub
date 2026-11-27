package com.lucaslu.tickhub.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight on-device debug logger. Writes a rolling text file so users can
 * export logs for diagnosis. NEVER log tokens, device codes, or credentials.
 */
object AppLogger {

    private const val TAG = "TickHub"
    private const val LOG_FILE = "tickhub-debug.log"
    const val CRASH_FILE = "tickhub-crash.txt" // written by TickHubApp crash handler
    private const val MAX_LINES = 800

    private var logFile: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()
    private var writesSinceTrim = 0
    private const val TRIM_EVERY = 50 // readLines() rewrites the whole file — don't do it per line

    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.applicationContext.filesDir, LOG_FILE)
        }
    }

    fun d(tag: String, message: String) = write("D", tag, message)
    fun i(tag: String, message: String) = write("I", tag, message)
    fun w(tag: String, message: String) = write("W", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        write("E", tag, message)
        throwable?.let { write("E", tag, "  ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun write(level: String, tag: String, message: String) {
        // SimpleDateFormat is NOT thread-safe — format inside the lock.
        synchronized(lock) {
        val line = "${fmt.format(Date())} $level/$tag: $message"
        when (level) {
            "D" -> Log.d(TAG, line)
            "I" -> Log.i(TAG, line)
            "W" -> Log.w(TAG, line)
            "E" -> Log.e(TAG, line)
        }
            val file = logFile ?: return
            try {
                if (!file.exists()) file.createNewFile()
                file.appendText(line + "\n")
                if (++writesSinceTrim >= TRIM_EVERY) {
                    writesSinceTrim = 0
                    trim(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "write log failed: ${e.message}")
            }
        }
    }

    private fun trim(file: File) {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        val kept = lines.takeLast(MAX_LINES)
        file.writeText(kept.joinToString("\n") + "\n")
    }

    fun readAll(): String = synchronized(lock) {
        val file = logFile ?: return L10n.tNow("日志未初始化")
        val log = try {
            if (file.exists()) file.readText() else L10n.tNow("（暂无日志）")
        } catch (e: Exception) {
            L10n.tFormat("读取日志失败：{0}", listOf(e.message.orEmpty()))
        }
        // Append last crash stack (if any) so flash-crashes are diagnosable.
        val crash = try {
            val f = java.io.File(file.parentFile, CRASH_FILE)
            if (f.exists()) "\n\n===== LAST CRASH =====\n${f.readText()}" else ""
        } catch (e: Exception) { "" }
        log + crash
    }

    fun clear() = synchronized(lock) {
        logFile?.writeText("")
    }

    fun getFile(): File? = logFile
}
