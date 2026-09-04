package com.morselink.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.morselink.app.MainActivity
import com.morselink.app.Notifications
import com.morselink.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §6 / API 23+: active transfers and discovery live in a foreground service so
 * Doze cannot kill the sockets mid-transfer.
 */
@AndroidEntryPoint
class ConnectionService : Service() {

    @Inject
    lateinit var updater: TransferNotificationUpdater

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_TRANSFER,
            buildNotification("Starting transfer…"),
            if (Build.VERSION.SDK_INT >= 34) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else 0,
        )
        observeProgress()
        return START_STICKY
    }

    private fun observeProgress() {
        scope.launch {
            updater.text.collect { text ->
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    manager?.notify(Notifications.ID_TRANSFER, buildNotification(text))
                }
            }
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, Notifications.CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle("Morselink transfer")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, ConnectionService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.morselink.app.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}

/** Formats live transfer state into a single line for the ongoing notification. */
class TransferNotificationUpdater @Inject constructor(
    private val engine: com.morselink.core.transfer.engine.TransferEngine,
) {
    val text: kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            emit("Transfer in progress")
            engine.state.collect { state ->
                val active = state.all().count { !it.isFinished }
                val bytes = state.all().sumOf { it.bytesTransferred }
                val total = state.all().sumOf { it.sizeBytes }
                if (active == 0) emit("Idle") else {
                    val percent = if (total > 0) (bytes * 100 / total) else 0
                    emit("$active file(s) · $percent%")
                }
            }
        }
}
