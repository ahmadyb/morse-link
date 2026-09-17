package com.morselink.core.ui

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared formatting helpers — every screen formats numbers the same way. */
object Format {

    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024

    fun bytes(value: Long): String = when {
        value <= 0 -> "0 B"
        value < KB -> "$value B"
        value < MB -> "%.1f KB".format(value / KB)
        value < GB -> "%.1f MB".format(value / MB)
        else -> "%.2f GB".format(value / GB)
    }

    fun speed(bytesPerSecond: Long): String = when {
        bytesPerSecond <= 0 -> "0 KB/s"
        bytesPerSecond < MB -> "%.0f KB/s".format(bytesPerSecond / KB)
        else -> "%.1f MB/s".format(bytesPerSecond / MB)
    }

    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    fun eta(seconds: Long): String {
        if (seconds < 0) return "--:--"
        val minutes = seconds / 60
        val secs = seconds % 60
        return if (minutes >= 60) {
            "%d:%02d:%02d".format(minutes / 60, minutes % 60, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    private fun dateFormat(pattern: String) =
        SimpleDateFormat(pattern, Locale.getDefault())

    fun fullDate(timestamp: Long): String =
        dateFormat("MMMM d, yyyy").format(Date(timestamp))

    fun shortDate(timestamp: Long): String =
        dateFormat("yyyy-MM-dd").format(Date(timestamp))

    fun time(timestamp: Long): String =
        dateFormat("HH:mm").format(Date(timestamp))

    /** "Today" / "Yesterday" / a full date — used by the date-grouped grids. */
    fun dayLabel(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = calendar.timeInMillis
        val oneDay = 24L * 60 * 60 * 1000
        return when {
            timestamp >= startOfToday -> "Today"
            timestamp >= startOfToday - oneDay -> "Yesterday"
            timestamp >= startOfToday - 6 * oneDay -> dateFormat("EEEE").format(Date(timestamp))
            else -> dateFormat("MMMM d, yyyy").format(Date(timestamp))
        }
    }

    fun isSameDay(a: Long, b: Long): Boolean {
        val calA = Calendar.getInstance().apply { timeInMillis = a }
        val calB = Calendar.getInstance().apply { timeInMillis = b }
        return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
            calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
    }

    fun plural(context: Context, count: Int, one: String, many: String): String =
        if (count == 1) "$count $one" else "$count $many"
}
