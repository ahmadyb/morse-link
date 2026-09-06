package com.morselink.core.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * §7 — API level alone is not a reliable proxy for hardware capability, so the
 * tier flag is computed from three signals and used only to gate cosmetic or
 * performance-tunable behaviour (never core functionality).
 */
object DeviceTier {

    @Volatile
    private var cached: Boolean? = null

    fun isLowEnd(context: Context): Boolean {
        cached?.let { return it }
        val lowEnd = compute(context)
        cached = lowEnd
        return lowEnd
    }

    private fun compute(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager?.isLowRamDevice == true) return true
        return Runtime.getRuntime().availableProcessors() <= 2
    }

    /** Thread pool sizing for the legacy Wi-Fi Direct chunk handlers (§7). */
    fun chunkThreads(): Int = if (Build.VERSION.SDK_INT < 23) 2 else
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** Thumbnail decode target in pixels (§7). */
    fun thumbnailTargetPx(context: Context): Int = if (isLowEnd(context)) 160 else 320
}
