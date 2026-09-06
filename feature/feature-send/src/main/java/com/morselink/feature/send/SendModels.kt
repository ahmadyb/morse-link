package com.morselink.feature.send

import android.net.Uri
import com.morselink.core.media.AppItem
import com.morselink.core.media.FileItem
import com.morselink.core.media.MediaItem
import com.morselink.core.media.SmartCategory
import com.morselink.core.media.SortOrder
import com.morselink.core.ui.Format

/** One row in the Send list, whatever the category. */
sealed interface SendRow {
    val key: String
    val name: String
    val sizeBytes: Long
    val mimeType: String?
    val uri: Uri?
    val path: String?
    /** Sort key in milliseconds; 0 for rows that have no meaningful date. */
    val timestamp: Long
    /** Date bucket (or fixed bucket) this row belongs to, for grouping. */
    val groupKey: String

    /** A date-group separator. Tapping it selects the whole group (§14.2). */
    data class Header(
        val label: String,
        val group: String,
        val count: Int,
        val allSelected: Boolean,
    ) : SendRow {
        override val key: String get() = "header:$group"
        override val name: String get() = label
        override val sizeBytes: Long get() = 0L
        override val mimeType: String? get() = null
        override val uri: Uri? get() = null
        override val path: String? get() = null
        override val timestamp: Long get() = 0L
        override val groupKey: String get() = group
    }

    data class Media(val item: MediaItem) : SendRow {
        override val key: String get() = "media:${item.uri}"
        override val name: String get() = item.displayName
        override val sizeBytes: Long get() = item.sizeBytes
        override val mimeType: String? get() = item.mimeType
        override val uri: Uri? get() = item.uri
        override val path: String? get() = item.path
        override val timestamp: Long get() = item.dateModified
        override val groupKey: String get() = Format.shortDate(item.dateModified)
    }

    data class App(val app: AppItem) : SendRow {
        override val key: String get() = "app:${app.packageName}"
        override val name: String get() = app.label
        override val sizeBytes: Long get() = app.sizeBytes
        override val mimeType: String? get() = "application/vnd.android.package-archive"
        override val uri: Uri? get() = null
        override val path: String? get() = app.apkPath
        override val timestamp: Long get() = 0L
        override val groupKey: String get() = GROUP_APPS
    }

    data class File(val file: FileItem) : SendRow {
        override val key: String get() = "file:${file.path}"
        override val name: String get() = file.name
        override val sizeBytes: Long get() = file.sizeBytes
        override val mimeType: String? get() = file.mimeType
        override val uri: Uri? get() = file.uri
        override val path: String? get() = file.path
        override val timestamp: Long get() = file.lastModified
        override val groupKey: String get() = Format.shortDate(file.lastModified)
    }

    /** A smart-category shortcut row (§14.3). */
    data class Category(val category: SmartCategory, val count: Int) : SendRow {
        override val key: String get() = "cat:${category.name}"
        override val name: String get() = category.name
        override val sizeBytes: Long get() = 0L
        override val mimeType: String? get() = null
        override val uri: Uri? get() = null
        override val path: String? get() = null
        override val timestamp: Long get() = 0L
        override val groupKey: String get() = GROUP_CATEGORIES
    }

    companion object {
        const val GROUP_APPS = "installed-apps"
        const val GROUP_CATEGORIES = "smart-categories"
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

fun SortOrder.toIndex(): Int = when (this) {
    SortOrder.SIZE -> 1
    SortOrder.NAME -> 2
    else -> 0
}
