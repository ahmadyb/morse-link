package com.morselink.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** MediaStore queries for photos, videos and music (§6 — scoped storage from API 29). */
@Singleton
class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun photos(sort: SortOrder = SortOrder.DATE): List<MediaItem> = withContext(Dispatchers.IO) {
        query(
            collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            sort = sort,
            extraColumns = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
        ) { columns -> columns.copy(bucketName = columns.bucket()) }
    }

    suspend fun videos(sort: SortOrder = SortOrder.DATE): List<MediaItem> = withContext(Dispatchers.IO) {
        query(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            sort = sort,
            extraColumns = arrayOf(
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            ),
        ) { columns -> columns.copy(durationMs = columns.duration(), bucketName = columns.bucket()) }
    }

    suspend fun music(sort: SortOrder = SortOrder.DATE): List<MediaItem> = withContext(Dispatchers.IO) {
        query(
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            sort = sort,
            extraColumns = arrayOf(
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
            ),
        ) { columns ->
            columns.copy(
                durationMs = columns.duration(),
                artist = columns.artist(),
                album = columns.album(),
            )
        }
    }

    private fun query(
        collection: Uri,
        sort: SortOrder,
        extraColumns: Array<String>,
        map: (MediaItem) -> MediaItem,
    ): List<MediaItem> {
        val base = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val orderBy = when (sort) {
            SortOrder.DATE -> "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            SortOrder.SIZE -> "${MediaStore.MediaColumns.SIZE} DESC"
            SortOrder.NAME -> "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
        }
        val items = mutableListOf<MediaItem>()
        // A few OEM builds omit optional columns, so fall back to the minimal projection.
        for (projection in listOf(base + extraColumns, base)) {
            items.clear()
            val success = runCatching {
                resolver.query(collection, projection, null, null, orderBy)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val item = cursor.toMediaItem(collection) ?: continue
                        items.add(map(item))
                    }
                }
            }.isSuccess
            if (success) break
        }
        return items
    }

    private fun Cursor.toMediaItem(collection: Uri): MediaItem? {
        val id = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        val name = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: return null
        val size = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
        val mime = optString(MediaStore.MediaColumns.MIME_TYPE)
        val modified = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000L
        val path = if (Build.VERSION.SDK_INT < 29) optString(MediaStore.MediaColumns.DATA) else null
        return MediaItem(
            id = id,
            uri = ContentUris.withAppendedId(collection, id),
            displayName = name,
            sizeBytes = size,
            mimeType = mime,
            dateModified = modified,
            path = path,
        )
    }

    private fun Cursor.optString(name: String): String? =
        getColumnIndex(name).takeIf { it >= 0 }?.let { getString(it) }

    private fun Cursor.optLong(name: String): Long =
        getColumnIndex(name).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L

    private fun Cursor.duration(): Long = optLong(MediaStore.Video.Media.DURATION)
    private fun Cursor.bucket(): String? = optString(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
    private fun Cursor.artist(): String? = optString(MediaStore.Audio.Media.ARTIST)
    private fun Cursor.album(): String? = optString(MediaStore.Audio.Media.ALBUM)
}
