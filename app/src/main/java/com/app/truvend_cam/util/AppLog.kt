package com.app.truvend_cam.util

import android.util.Log
import com.app.truvend_cam.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Structured logging with credential redaction and an in-memory ring buffer
 * for the on-device diagnostic screen.
 */
object AppLog {

    private const val MAX_ENTRIES = 500
    private val entries = CopyOnWriteArrayList<String>()
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    fun i(tag: String, message: String) = log(Log.INFO, tag, redact(message))
    fun w(tag: String, message: String) = log(Log.WARN, tag, redact(message))
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            "${redact(message)} | ${throwable.javaClass.simpleName}: ${redact(throwable.message ?: "")}"
        } else {
            redact(message)
        }
        log(Log.ERROR, tag, msg)
    }
    fun d(tag: String, message: String) {
        if (enabled) log(Log.DEBUG, tag, redact(message))
    }

    fun snapshot(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
    }

    private fun log(priority: Int, tag: String, message: String) {
        val line = "${levelName(priority)}/$tag: $message"
        entries.add(line)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        if (enabled || priority >= Log.WARN) {
            Log.println(priority, "TruvendCam/$tag", message)
        }
    }

    private fun levelName(priority: Int): String = when (priority) {
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "?"
    }

    /** Strip passwords from URLs and common key=value patterns. */
    fun redact(input: String): String {
        var s = input
        s = s.replace(Regex("://([^:/\\s]+):([^@/\\s]+)@"), "://$1:***@")
        s = s.replace(Regex("(?i)(password|passwd|pwd)\\s*[=:]\\s*\\S+"), "$1=***")
        s = s.replace(Regex("(?i)\"password\"\\s*:\\s*\"[^\"]*\""), "\"password\":\"***\"")
        return s
    }
}
