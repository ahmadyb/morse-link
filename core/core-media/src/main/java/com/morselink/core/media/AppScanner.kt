package com.morselink.core.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installed apps only — no recommendations, no sponsored entries (§14.2).
 * Uses an intent query so package visibility keeps working on API 30+ (§6).
 */
@Singleton
class AppScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun installedApps(): List<AppItem> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val apkPath = info.activityInfo.applicationInfo.sourceDir
            val size = runCatching { File(apkPath).length() }.getOrDefault(0L)
            AppItem(
                packageName = packageName,
                label = info.loadLabel(packageManager)?.toString() ?: packageName,
                versionName = runCatching {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull(),
                sizeBytes = size,
                apkPath = apkPath,
            )
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
