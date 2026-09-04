package com.morselink.feature.send

import android.net.Uri
import com.morselink.core.media.AppItem
import com.morselink.core.media.FileItem
import com.morselink.core.media.MediaItem
import com.morselink.core.media.SmartCategory
import com.morselink.core.media.SortOrder

/** One row in the Send list, whatever the category. */
sealed interface SendRow {
    val key: String
    val name: String
    val sizeBytes: Long
    val mimeType: String?
    val uri: Uri?
    val path: String?

    data class Media(val item: MediaItem) : SendRow {
        override val key: String get() = "media:${item.uri}"
        override val name: String get() = item.displayName
        override val sizeBytes: Long get() = item.sizeBytes
        override val mimeType: String? get() = item.mimeType
        override val uri: Uri? get() = item.uri
        override val path: String? get() = item.path
    }

    data class App(val app: AppItem) : SendRow {
        override val key: String get() = "app:${app.packageName}"
        override val name: String get() = app.label
        override val sizeBytes: Long get() = app.sizeBytes
        override val mimeType: String? get() = "application/vnd.android.package-archive"
        override val uri: Uri? get() = null
        override val path: String? get() = app.apkPath
    }

    data class File(val file: FileItem) : SendRow {
        override val key: String get() = "file:${file.path}"
        override val name: String get() = file.name
        override val sizeBytes: Long get() = file.sizeBytes
        override val mimeType: String? get() = file.mimeType
        override val uri: Uri? get() = file.uri
        override val path: String? get() = file.path
    }

    /** A smart-category shortcut row (§14.3). */
    data class Category(val category: SmartCategory, val count: Int) : SendRow {
        override val key: String get() = "cat:${category.name}"
        override val name: String get() = category.name
        override val sizeBytes: Long get() = 0L
        override val mimeType: String? get() = null
        override val uri: Uri? get() = null
        override val path: String? get() = null
    }
}

enum class SendTab(val title: String, val useGrid: Boolean) {
    PHOTOS("Photos", useGrid = true),
    VIDEOS("Videos", useGrid = true),
    MUSIC("Music", useGrid = false),
    APPS("Apps", useGrid = true),
    FILES("Files", useGrid = false),
}

fun SortOrder.fromIndex(index: Int): SortOrder = when (index) {
    1 -> SortOrder.SIZE
    2 -> SortOrder.NAME
    else -> SortOrder.DATE
}
