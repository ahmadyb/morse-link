package com.morselink.core.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
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
    private const val KEY_CLEARED_AT = "cleared_at"
    private const val FILE_NAME = "app.log"
    private const val MAX_BYTES = 256 * 1024

    private const val EXPORT_DIR = "logs"
    private const val EXPORT_PREFIX = "morselink-log-"

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
                append(dropCleared(context, text).ifBlank { "(empty)" })
            }.trim()
        }.getOrDefault("Logcat is not available on this device.")
    }

    /**
     * Writes the whole log to a .txt file named for when it was taken, so it can
     * be attached to a message instead of copied out of a dialog in pieces.
     *
     * Returns the file, or null if there was nothing to write or nowhere to put
     * it. The file lives under the app's external files directory, which needs
     * no storage permission and is shared through the FileProvider.
     */
    suspend fun export(context: Context): File? = withContext(Dispatchers.IO) {
        val text = capture(context)
        if (text.isBlank()) return@withContext null
        runCatching {
            val directory = File(context.getExternalFilesDir(null), EXPORT_DIR)
            if (!directory.exists() && !directory.mkdirs()) return@runCatching null
            val name = EXPORT_PREFIX +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            val target = File(directory, name)
            target.writeText(text)
            target
        }.getOrNull()
    }

    /**
     * Clears both halves of what the viewer shows.
     *
     * Deleting our own file was never enough on its own: the viewer also prints
     * live logcat, which lives in a system buffer this app cannot clear (that
     * needs READ_LOGS, withdrawn from third-party apps back at API 16). So the
     * moment of clearing is recorded and older logcat lines are dropped instead.
     */
    fun clear(context: Context) {
        prefs(context).edit()
            .putLong(KEY_CLEARED_AT, System.currentTimeMillis())
            .apply()
        runCatching { file(context).delete() }
    }

    /** Drops logcat lines older than the last clear. */
    private fun dropCleared(context: Context, text: String): String {
        val since = prefs(context).getLong(KEY_CLEARED_AT, 0L)
        if (since <= 0L) return text
        val out = StringBuilder()
        var keeping = false
        for (line in text.lineSequence()) {
            val stamp = parseLogcatTime(line)
            if (stamp == null) {
                // A continuation line (stack frames have no stamp of their own)
                // belongs to whatever timestamped line came before it.
                if (keeping) out.appendLine(line)
                continue
            }
            keeping = stamp >= since
            if (keeping) out.appendLine(line)
        }
        return out.toString()
    }

    /**
     * logcat -v time stamps lines "MM-dd HH:mm:ss.SSS" with no year, so the year
     * is taken from today. A stamp that lands more than a day in the future is
     * last December, not next year.
     */
    private fun parseLogcatTime(line: String, nowMs: Long = System.currentTimeMillis()): Long? {
        if (line.length < 18) return null
        val parsed = runCatching {
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).parse(line.substring(0, 18))
        }.getOrNull() ?: return null
        val stamp = Calendar.getInstance().apply { time = parsed }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        stamp.set(Calendar.YEAR, now.get(Calendar.YEAR))
        if (stamp.timeInMillis > nowMs + 86_400_000L) stamp.add(Calendar.YEAR, -1)
        return stamp.timeInMillis
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
