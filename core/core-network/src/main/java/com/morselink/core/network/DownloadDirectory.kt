package com.morselink.core.network

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where an inbound file is staged before it is published to MediaStore.
 *
 * Kept local to :core:core-network on purpose — the transport layer must not
 * depend on :core:core-media just to ask for a folder (that would drag the
 * image loader and the MediaStore code into every transport).
 *
 * Public storage is preferred so the user can find the file, but only when it
 * is genuinely writable; otherwise we fall back to app-specific storage, which
 * never needs a permission on any API level.
 */
internal fun defaultDownloadDirectory(context: Context): File {
    val candidates = listOfNotNull(
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull(),
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        context.filesDir,
    )
    for (base in candidates) {
        val target = File(base, "Morselink")
        if (target.isDirectory && target.canWrite()) return target
        if (target.mkdirs() && target.canWrite()) return target
    }
    return File(context.filesDir, "Morselink").apply { mkdirs() }
}
