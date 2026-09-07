package com.morselink.core.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * An opt-in log a user can switch on, read, and paste into a bug report.
 *
 * It is off by default and nothing is written until it is enabled — the app
 * ships with no analytics and no telemetry, and this is meant to stay that way.
 * Capturing uses logcat, which on modern Android only ever returns this app's
 * own output.
 */
object AppLog {

    private const val PREFS = "app_log_state"
    private const val KEY_ENABLED = "enabled"
    private const val FILE_NAME = "app.log"
    private const val MAX_BYTES = 256 * 1024

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun log(context: Context, tag: String, message: String) {
        if (!isEnabled(context)) return
        val line = "${stamp()} $tag: $message\n"
        runCatching {
            val target = file(context)
            if (target.length() > MAX_BYTES) target.writeText("")
            FileOutputStream(target, true).use { it.write(line.toByteArray()) }
        }
    }

    /**
     * Everything the app has written to logcat. This is the thing a user can
     * copy and paste so a problem can be diagnosed without a USB cable.
     */
    suspend fun capture(context: Context): String = withContext(Dispatchers.IO) {
        if (!isEnabled(context)) return@withContext "Logging is switched off."
        runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-s", "Morselink:*", "*:E")
            )
            val text = process.inputStream.bufferedReader().readText()
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            val own = runCatching { file(context).readText() }.getOrDefault("")
            buildString {
                if (own.isNotBlank()) {
                    appendLine("----- in-app log -----")
                    append(own)
                    appendLine()
                }
                appendLine("----- logcat -----")
                append(text.ifBlank { "(empty)" })
            }.trim()
        }.getOrDefault("Logcat is not available on this device.")
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
