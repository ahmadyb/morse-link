package com.morselink.core.media

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Why a folder came back with nothing in it. */
enum class DirectoryState { OK, EMPTY, RESTRICTED, MISSING }

data class DirectoryListing(
    val items: List<FileItem>,
    val state: DirectoryState,
)

/**
 * Raw folder browsing plus the smart-category filtering used by the Files tab.
 *
 * Two rules drive this file, both learned the hard way:
 *
 * 1. **Never report "empty" when the truth is "can't read".** From API 30
 *    scoped storage blocks `File.listFiles()` on other apps' `Android/data`
 *    trees, which returns null. Treating that as an empty folder made every
 *    nested directory look like it had nothing in it.
 * 2. **One traversal, with a budget.** Counting five categories used to mean
 *    five independent recursive walks of the whole storage root, which took
 *    the better part of a minute on a full phone. Now it is a single pass
 *    that tallies every category at once and stops at a deadline.
 */
@Singleton
class FileBrowser @Inject constructor() {

    companion object {
        private const val MAX_DEPTH = 4
        private const val SCAN_BUDGET_MS = 4_000L
        private const val MAX_MATCHES = 2_000

        /** A folder's child count is not computed during a listing — see below. */
        private const val UNKNOWN_CHILD_COUNT = -1

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

        /**
         * Same as [storageRoots] but also picks up removable volumes.
         *
         * `getExternalFilesDirs()` returns the primary emulated volume first and
         * any SD card or USB OTG volume after it, each rooted somewhere under
         * /storage/<VOLUME>/Android/data/<pkg>/files — so the volume root has to
         * be recovered by walking back up to /storage.
         */
        fun storageRoots(context: Context): List<File> {
            val roots = LinkedHashMap<String, File>()
            runCatching {
                Environment.getExternalStorageDirectory()?.let {
                    roots.putIfAbsent(it.absolutePath, it)
                }
            }
            runCatching {
                context.getExternalFilesDirs(null)?.forEach { dir ->
                    val volume = dir?.let { volumeRootOf(it) } ?: return@forEach
                    roots.putIfAbsent(volume.absolutePath, volume)
                }
            }
            runCatching {
                context.getExternalMediaDirs()?.forEach { dir ->
                    val volume = dir?.let { volumeRootOf(it) } ?: return@forEach
                    roots.putIfAbsent(volume.absolutePath, volume)
                }
            }
            // Last resort for devices that report nothing through the APIs.
            if (roots.size <= 1) {
                runCatching {
                    File("/storage").listFiles()?.forEach { child ->
                        if (child.isDirectory && child.canRead() &&
                            child.absolutePath != "/storage/emulated"
                        ) {
                            roots.putIfAbsent(child.absolutePath, child)
                        }
                    }
                }
            }
            runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    ?.let { roots.putIfAbsent(it.absolutePath, it) }
            }
            if (roots.isEmpty()) roots["/storage/emulated/0"] = File("/storage/emulated/0")
            return roots.values.filter { it.exists() }
        }

        /**
         * /storage/ABCD-1234/Android/data/<pkg>/files -> /storage/ABCD-1234.
         * Returns null when the walk never reaches a /storage child.
         */
        private fun volumeRootOf(dir: File): File? {
            var cursor: File? = dir
            while (cursor != null) {
                val parent = cursor.parentFile
                if (parent == null || parent.absolutePath == "/storage" ||
                    parent.absolutePath == "/"
                ) {
                    return cursor.takeIf { it.exists() && it.isDirectory }
                }
                cursor = parent
            }
            return null
        }

        /** A friendly name for a storage root, instead of a raw volume id. */
        fun volumeLabel(path: String): String = when {
            path == Environment.getExternalStorageDirectory()?.absolutePath ||
                path.contains("/emulated/") -> "Phone storage"
            path.startsWith("/storage/") -> "SD card"
            else -> File(path).name
        }

        /** A storage root presented as a browsable folder. */
        fun asItem(file: File): FileItem = FileItem(
            path = file.absolutePath,
            name = volumeLabel(file.absolutePath),
            isDirectory = true,
            sizeBytes = 0L,
            lastModified = file.lastModified(),
            mimeType = null,
            childCount = UNKNOWN_CHILD_COUNT,
            canRead = file.canRead(),
        )
    }

    // ------------------------------------------------------------- directories

    suspend fun listDirectory(path: String): DirectoryListing = withContext(Dispatchers.IO) {
        val directory = File(path)
        when {
            !directory.exists() || !directory.isDirectory ->
                DirectoryListing(emptyList(), DirectoryState.MISSING)
            !directory.canRead() ->
                DirectoryListing(emptyList(), DirectoryState.RESTRICTED)
            else -> {
                val children = runCatching { directory.listFiles() }.getOrNull()
                when {
                    children == null -> DirectoryListing(emptyList(), DirectoryState.RESTRICTED)
                    children.isEmpty() -> DirectoryListing(emptyList(), DirectoryState.EMPTY)
                    else -> DirectoryListing(children.toFileItems(), DirectoryState.OK)
                }
            }
        }
    }

    private fun Array<File>.toFileItems(): List<FileItem> = asSequence()
        .filter { !it.name.startsWith(".") }
        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        .map { file ->
            FileItem(
                path = file.absolutePath,
                name = file.name,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
                mimeType = if (file.isDirectory) null else mimeOf(file.name),
                // Counting a subfolder's children means listing it, which is far
                // too expensive to do for every row. -1 means "unknown" and the
                // UI just says "Folder".
                childCount = if (file.isDirectory) UNKNOWN_CHILD_COUNT else 0,
                canRead = if (file.isDirectory) file.canRead() else true,
            )
        }
        .toList()

    // --------------------------------------------------------------- categories

    suspend fun category(category: SmartCategory): List<FileItem> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
        val matches = mutableListOf<FileItem>()
        for (root in storageRoots()) {
            walkForCategory(root, 0, matches, category, deadline)
            if (matches.size >= MAX_MATCHES) break
        }
        matches.sortedByDescending { it.lastModified }
    }

    /** One traversal tallies every category at once. */
    suspend fun counts(): Map<SmartCategory, Int> = withContext(Dispatchers.IO) {
        val tally = SmartCategory.values().associateWith { 0 }.toMutableMap()
        val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
        for (root in storageRoots()) {
            walkForCounts(root, 0, tally, deadline)
        }
        tally
    }

    private fun walkForCategory(
        directory: File,
        depth: Int,
        sink: MutableList<FileItem>,
        category: SmartCategory,
        deadline: Long,
    ) {
        if (depth > MAX_DEPTH || sink.size >= MAX_MATCHES) return
        if (System.currentTimeMillis() > deadline) return
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (sink.size >= MAX_MATCHES || System.currentTimeMillis() > deadline) return
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                if (isRestricted(child)) continue
                walkForCategory(child, depth + 1, sink, category, deadline)
            } else if (matches(child, category)) {
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

    private fun walkForCounts(
        directory: File,
        depth: Int,
        tally: MutableMap<SmartCategory, Int>,
        deadline: Long,
    ) {
        if (depth > MAX_DEPTH) return
        if (System.currentTimeMillis() > deadline) return
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (System.currentTimeMillis() > deadline) return
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                if (isRestricted(child)) continue
                walkForCounts(child, depth + 1, tally, deadline)
                continue
            }
            val extension = child.extension.lowercase()
            val size = child.length()
            for (category in SmartCategory.values()) {
                if (matches(category, extension, size)) {
                    tally[category] = (tally[category] ?: 0) + 1
                }
            }
        }
    }

    private fun matches(file: File, category: SmartCategory): Boolean =
        matches(category, file.extension.lowercase(), file.length())

    private fun matches(category: SmartCategory, extension: String, size: Long): Boolean =
        if (category == SmartCategory.LARGE_FILES) size >= category.minSizeBytes
        else extension in category.extensions

    /**
     * `Android/data` and `Android/obb` are blocked by scoped storage from API 30,
     * and walking them is pure cost: they return null and are enormous.
     */
    private fun isRestricted(directory: File): Boolean {
        if (!directory.canRead()) return true
        val parent = directory.parentFile?.name ?: return false
        return parent == "Android" && (directory.name == "data" || directory.name == "obb")
    }

    fun mimeOf(name: String): String? {
        val extension = name.substringAfterLast('.', "")
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }
}
