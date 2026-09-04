package com.morselink.core.media

import android.os.Environment
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw folder browsing plus the smart-category filtering used by the Files tab.
 * Depth and node counts are capped so a phone with tens of thousands of files
 * still returns quickly on low-end hardware (§7).
 */
@Singleton
class FileBrowser @Inject constructor() {

    companion object {
        private const val MAX_NODES = 4000
        private const val MAX_DEPTH = 4

        fun storageRoots(): List<File> {
            val roots = mutableListOf<File>()
            runCatching { Environment.getExternalStorageDirectory()?.let { roots.add(it) } }
            runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    ?.let { roots.add(it) }
            }
            if (roots.isEmpty()) roots.add(File("/storage/emulated/0"))
            return roots.distinctBy { it.absolutePath }.filter { it.exists() }
        }
    }

    suspend fun listDirectory(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        val directory = File(path)
        if (!directory.isDirectory) return@withContext emptyList()
        val entries = (directory.listFiles()?.toList() ?: emptyList())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        entries.map { file ->
            FileItem(
                path = file.absolutePath,
                name = file.name,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
                mimeType = if (file.isDirectory) null else mimeOf(file.name),
                childCount = if (file.isDirectory) file.listFiles()?.size ?: 0 else 0,
            )
        }
    }

    suspend fun category(category: SmartCategory): List<FileItem> = withContext(Dispatchers.IO) {
        val matches = mutableListOf<FileItem>()
        for (root in storageRoots()) {
            walk(root, 0, matches) { file ->
                when (category) {
                    SmartCategory.LARGE_FILES -> file.length() >= category.minSizeBytes
                    else -> file.extension.lowercase() in category.extensions
                }
            }
            if (matches.size >= MAX_NODES) break
        }
        matches.sortedByDescending { it.lastModified }
    }

    suspend fun counts(): Map<SmartCategory, Int> = withContext(Dispatchers.IO) {
        SmartCategory.values().associateWith { runCatching { category(it).size }.getOrDefault(0) }
    }

    private fun walk(
        directory: File,
        depth: Int,
        sink: MutableList<FileItem>,
        predicate: (File) -> Boolean,
    ) {
        if (depth > MAX_DEPTH || sink.size >= MAX_NODES) return
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (sink.size >= MAX_NODES) return
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                walk(child, depth + 1, sink, predicate)
            } else if (predicate(child)) {
                sink.add(
                    FileItem(
                        path = child.absolutePath,
                        name = child.name,
                        isDirectory = false,
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                        mimeType = mimeOf(child.name),
                    )
                )
            }
        }
    }

    fun mimeOf(name: String): String? {
        val extension = name.substringAfterLast('.', "")
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }
}
