package com.morselink.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.morselink.core.ui.Dialogs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6 — every permission request is version-gated, and every denial offers a
 * route back into app settings (§10).
 */
@Singleton
class Permissions @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(permissions: Collection<String>): List<String> =
        permissions.filter { it.isNotBlank() && !granted(it) }

    fun forDiscovery(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        Build.VERSION.SDK_INT >= 31 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        Build.VERSION.SDK_INT >= 23 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList() // install-time permissions on API 21-22
    }

    fun forMedia(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        Build.VERSION.SDK_INT >= 23 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyList()
    }

    fun forCamera(): List<String> = listOf(Manifest.permission.CAMERA)

    fun forNotifications(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** API 30+ needs a special settings screen for full file access (File Manager). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun requestAllFilesAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
            )
        }.onFailure {
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    fun request(activity: Activity, permissions: List<String>, requestCode: Int): Boolean {
        val needed = missing(permissions)
        if (needed.isEmpty()) return true
        ActivityCompat.requestPermissions(activity, needed.toTypedArray(), requestCode)
        return false
    }

    /** §10 — explain, then offer app settings instead of a dead end. */
    fun explain(activity: Activity, title: String, message: String) {
        Dialogs.permissionRationale(activity, title, message)
    }
}
