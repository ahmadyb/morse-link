package com.morselink.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** §6 / API 26: notification channels are mandatory from Android 8. */
object Notifications {

    const val CHANNEL_TRANSFERS = "transfers"
    const val CHANNEL_WEBSHARE = "webshare"
    const val ID_TRANSFER = 1001
    const val ID_WEBSHARE = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(CHANNEL_TRANSFERS, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Active and completed file transfers"
            },
            NotificationChannel(CHANNEL_WEBSHARE, "WebShare", NotificationManager.IMPORTANCE_LOW).apply {
                description = "WebShare server running on this device"
            },
        ).forEach { channel -> manager.createNotificationChannel(channel) }
    }
}
