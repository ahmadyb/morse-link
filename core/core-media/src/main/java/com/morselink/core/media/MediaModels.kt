package com.morselink.core.media

import android.net.Uri

enum class MediaCategory { PHOTOS, VIDEOS, MUSIC, APPS, FILES }

/** §14.3 — the five shortcut categories shown on the Files tab. */
enum class SmartCategory(val extensions: Set<String>, val minSizeBytes: Long = 0L) {
    DOCUMENTS(setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "odt", "rtf", "csv")),
    EBOOKS(setOf("epub", "mobi", "azw3", "fb2")),
    APKS(setOf("apk")),
    ARCHIVES(setOf("zip", "rar", "7z", "tar", "gz")),
    LARGE_FILES(emptySet(), minSizeBytes = 50L * 1024 * 1024),
}

enum class SortOrder { DATE, SIZE, NAME }

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val dateModified: Long,
    val durationMs: Long = 0L,
    val artist: String? = null,
    val album: String? = null,
    val bucketName: String? = null,
    val path: String? = null,
)

data class AppItem(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val sizeBytes: Long,
    val apkPath: String,
)

data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String?,
    val childCount: Int = 0,
    val uri: Uri? = null,
    /** False when scoped storage refused to open a folder, so the UI can say so. */
    val canRead: Boolean = true,
)

data class StorageInfo(
    val totalBytes: Long,
    val usedBytes: Long,
) {
    val freeBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)
    val usedFraction: Float get() = if (totalBytes == 0L) 0f else usedBytes.toFloat() / totalBytes
}
