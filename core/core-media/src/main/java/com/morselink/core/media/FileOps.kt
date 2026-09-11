package com.morselink.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** File Manager actions (§2.1) plus the save path used by incoming transfers. */
@Singleton
class FileOps @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    fun defaultDownloadDirectory(): File {
        val public = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        val base = public?.takeIf { it.exists() || it.mkdirs() }
            ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val target = File(base, "Morselink")
        if (!target.exists()) target.mkdirs()
        return target
    }

    suspend fun rename(file: File, newName: String): File = withContext(Dispatchers.IO) {
        val target = File(file.parentFile, newName)
        if (target.exists()) error("A file with that name already exists")
        if (!file.renameTo(target)) error("Rename failed — the system blocked access to this file")
        target
    }

    suspend fun delete(item: FileItem): Unit = withContext(Dispatchers.IO) {
        val file = File(item.path)
        if (file.delete()) return@withContext
        item.uri?.let { uri ->
            if (runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0) return@withContext
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            error("Android 11+ needs All files access to remove this item")
        } else {
            error("Delete failed — the system blocked access to this file")
        }
    }

    suspend fun copy(source: FileItem, targetDirectory: File): File = withContext(Dispatchers.IO) {
        val sourceFile = File(source.path)
        val target = uniqueFile(targetDirectory, sourceFile.name)
        if (sourceFile.isDirectory) {
            copyDirectory(sourceFile, target)
        } else {
            FileInputStream(sourceFile).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        }
        target
    }

    suspend fun move(source: FileItem, targetDirectory: File): File = withContext(Dispatchers.IO) {
        val sourceFile = File(source.path)
        val target = uniqueFile(targetDirectory, sourceFile.name)
        if (sourceFile.renameTo(target)) return@withContext target
        FileInputStream(sourceFile).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        sourceFile.delete()
        target
    }

    suspend fun compress(items: List<FileItem>, target: File): File = withContext(Dispatchers.IO) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for (item in items) {
                val file = File(item.path)
                if (file.isDirectory) continue
                zip.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        target
    }

    suspend fun storageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val path = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: context.filesDir
        val stat = StatFs(path.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val available = stat.availableBlocksLong * stat.blockSizeLong
        StorageInfo(totalBytes = total, usedBytes = total - available)
    }

    /** §10 — how much room is left before we accept an incoming file set. */
    fun usableSpace(): Long = runCatching {
        defaultDownloadDirectory().usableSpace
    }.getOrDefault(0L)

    /**
     * Insert a received file into MediaStore so other apps can see it (§2.1).
     *
     * Every date column has to be filled in. Received photos were showing in
     * the gallery as 1 January 1970, 01:00 - which is epoch zero read in
     * UTC+1 - because the legacy path went through
     * MediaStore.Images.Media.insertImage, whose Bitmap overload never sets
     * DATE_TAKEN, and the newer path set only DATE_ADDED. Galleries sort and
     * group by DATE_TAKEN, so an unset column is a photo dated 1970.
     *
     * DATE_TAKEN is in milliseconds; DATE_ADDED and DATE_MODIFIED are in
     * seconds. Mixing them up is its own way to land in 1970.
     */
    suspend fun publishToMediaStore(file: File, mimeType: String?): Uri? = withContext(Dispatchers.IO) {
        val nowMillis = System.currentTimeMillis()
        // Keep the file's own timestamp in step with what we tell MediaStore,
        // or file managers and MediaStore disagree about when it arrived.
        runCatching { file.setLastModified(nowMillis) }

        if (Build.VERSION.SDK_INT < 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, file.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.TITLE, file.nameWithoutExtension)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
                put(MediaStore.MediaColumns.SIZE, file.length())
                put(MediaStore.MediaColumns.DATE_ADDED, nowMillis / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, nowMillis / 1000)
                if (mimeType?.startsWith("image") == true) {
                    put(MediaStore.Images.ImageColumns.DATE_TAKEN, nowMillis)
                }
                if (mimeType?.startsWith("video") == true) {
                    put(MediaStore.Video.VideoColumns.DATE_TAKEN, nowMillis)
                }
            }
            val legacy = runCatching {
                resolver.insert(legacyCollection(mimeType), values)
            }.getOrNull()
            if (legacy != null) return@withContext legacy
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            return@withContext Uri.fromFile(file)
        }

        val collection = when {
            mimeType?.startsWith("video") == true -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType?.startsWith("audio") == true -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            mimeType?.startsWith("image") == true -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(MediaStore.MediaColumns.SIZE, file.length())
            put(MediaStore.MediaColumns.DATE_ADDED, nowMillis / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowMillis / 1000)
            if (mimeType?.startsWith("image") == true) {
                put(MediaStore.Images.ImageColumns.DATE_TAKEN, nowMillis)
            }
            if (mimeType?.startsWith("video") == true) {
                put(MediaStore.Video.VideoColumns.DATE_TAKEN, nowMillis)
            }
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Morselink")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@withContext null
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            }
            val clear = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, clear, null, null)
        }.onFailure {
            runCatching { resolver.delete(uri, null, null) }
        }
        uri
    }

    /** Below API 29 MediaStore is addressed by file path rather than by collection. */
    private fun legacyCollection(mimeType: String?): Uri = when {
        mimeType?.startsWith("video") == true -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        mimeType?.startsWith("audio") == true -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        mimeType?.startsWith("image") == true -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri("external")
    }

    suspend fun contentUriForMedia(item: MediaItem): Uri =
        ContentUris.withAppendedId(
            when {
                item.mimeType?.startsWith("video") == true -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                item.mimeType?.startsWith("audio") == true -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            },
            item.id,
        )

    private fun copyDirectory(source: File, target: File) {
        if (!target.exists()) target.mkdirs()
        source.listFiles()?.forEach { child ->
            val destination = File(target, child.name)
            if (child.isDirectory) copyDirectory(child, destination)
            else FileInputStream(child).use { input -> FileOutputStream(destination).use { input.copyTo(it) } }
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        if (!directory.exists()) directory.mkdirs()
        var candidate = File(directory, name)
        var index = 1
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        val hasExtension = extension.isNotEmpty() && extension != name
        while (candidate.exists()) {
            candidate = if (hasExtension) File(directory, "$base ($index).$extension")
            else File(directory, "$name ($index)")
            index++
        }
        return candidate
    }
}
