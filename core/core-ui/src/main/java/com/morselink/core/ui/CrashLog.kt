package com.morselink.core.ui

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records uncaught exceptions to a private file so a crash can be reported
 * without a USB cable. Installed once from the Application; read back from
 * Settings. This is a diagnostic aid — nothing leaves the device.
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val PREFS = "crash_log_state"
    private const val KEY_NEWEST = "newest_stamp"
    private const val KEY_SEEN = "seen_stamp"
    private const val MARKER = "==== CRASH "
    private const val MAX_ENTRIES = 5
    private const val MAX_CAUSE_DEPTH = 5

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** The recorded crashes, newest first, or null when there are none. */
    fun read(context: Context): String? =
        runCatching { file(context).readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** Just the newest entry, for showing in a dialog. */
    fun newest(text: String): String {
        val next = text.indexOf(MARKER, MARKER.length)
        return if (next > 0) text.substring(0, next) else text
    }

    /**
     * True when a crash has been recorded that the user has not seen yet.
     * The log has to be surfaced outside Settings — a crash in Settings
     * would otherwise make it permanently unreachable.
     */
    fun hasUnseen(context: Context): Boolean =
        read(context) != null && newestStamp(context) > seenStamp(context)

    fun newestStamp(context: Context): Long = prefs(context).getLong(KEY_NEWEST, 0L)

    fun seenStamp(context: Context): Long = prefs(context).getLong(KEY_SEEN, 0L)

    fun markSeen(context: Context) {
        prefs(context).edit().putLong(KEY_SEEN, newestStamp(context)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Number of crashes recorded in [text]. */
    fun entryCount(text: String): Int =
        text.split(MARKER).count { it.isNotBlank() }

    /**
     * Wraps any existing handler rather than replacing it, so the system still
     * shows its own crash dialog and the process still terminates as usual.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        runCatching { prefs(context).edit().putLong(KEY_NEWEST, now).apply() }
        val entry = buildString {
            append(MARKER).append(stamp()).appendLine()
            appendLine("thread: ${thread.name}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            appendLine("exception: ${throwable.javaClass.name}: ${throwable.message}")
            var depth = 0
            var cause: Throwable? = throwable
            while (cause != null && depth < MAX_CAUSE_DEPTH) {
                appendLine("--- ${cause.javaClass.name}: ${cause.message}")
                for (frame in cause.stackTrace) appendLine("    at $frame")
                cause = cause.cause
                depth++
            }
            appendLine()
        }

        // The process is dying, so write synchronously and keep the file small.
        val existing = runCatching { file(context).readText() }.getOrDefault("")
        val older = existing.split(MARKER).filter { it.isNotBlank() }.take(MAX_ENTRIES - 1)
        val merged = entry + older.joinToString("") { MARKER + it }
        runCatching {
            FileOutputStream(file(context), false).use { it.write(merged.toByteArray()) }
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
