package com.morselink.feature.filemanager

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morselink.core.media.DirectoryState
import com.morselink.core.media.FileBrowser
import com.morselink.core.media.AppItem
import com.morselink.core.media.FileItem
import com.morselink.core.media.FileOps
import com.morselink.core.media.MediaItem
import com.morselink.core.media.MediaRepository
import com.morselink.core.media.SmartCategory
import com.morselink.core.media.SortOrder
import com.morselink.core.media.StorageInfo
import com.morselink.core.ui.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class Breadcrumb(val label: String, val path: String)

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val media: MediaRepository,
    private val fileOps: FileOps,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _rows = MutableLiveData<List<FileRow>>(emptyList())
    val rows: LiveData<List<FileRow>> = _rows

    private val _breadcrumbs = MutableLiveData(listOf(Breadcrumb(ROOT_LABEL, "")))
    val breadcrumbs: LiveData<List<Breadcrumb>> = _breadcrumbs

    private val _storage = MutableLiveData(StorageInfo(0L, 0L))
    val storage: LiveData<StorageInfo> = _storage

    /** True while a scan or a directory read is in flight. */
    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    /** Why the list came back short — drives the message under the list. */
    private val _state = MutableLiveData(DirectoryState.OK)
    val state: LiveData<DirectoryState> = _state

    private val selection = LinkedHashMap<String, FileItem>()

    private val rootPath: String = runCatching {
        FileBrowser.storageRoots(context).firstOrNull()?.absolutePath
    }.getOrDefault("/storage/emulated/0") ?: "/storage/emulated/0"

    /** Empty means "the smart-category overview", which is the entry state. */
    private var currentPath: String = ""
    private var activeCategory: SmartCategory? = null
    private var activeLibrary: MediaLibrary? = null

    /** True while a media library is open, so the list can lay itself out as a
     *  gallery rather than as file rows. */
    private val _gallery = MutableLiveData(false)
    val gallery: LiveData<Boolean> = _gallery

    fun refresh() {
        viewModelScope.launch {
            _loading.postValue(true)
            _storage.postValue(
                runCatching { media.storageInfo() }.getOrDefault(StorageInfo(0L, 0L))
            )
            loadRows()
            _loading.postValue(false)
        }
    }

    private suspend fun loadRows() {
        val rows: List<FileRow>
        val state: DirectoryState

        when {
            activeLibrary != null -> {
                val items = libraryItems(activeLibrary!!)
                rows = items.map { FileRow.Entry(it) }
                state = if (rows.isEmpty()) DirectoryState.EMPTY else DirectoryState.OK
            }
            activeCategory != null -> {
                rows = runCatching { media.category(activeCategory!!) }
                    .getOrDefault(emptyList())
                    .map { FileRow.Entry(it) }
                state = if (rows.isEmpty()) DirectoryState.EMPTY else DirectoryState.OK
            }
            currentPath.isNotBlank() -> {
                val listing = runCatching { media.listDirectory(currentPath) }
                    .getOrDefault(
                        com.morselink.core.media.DirectoryListing(
                            emptyList(),
                            DirectoryState.RESTRICTED,
                        )
                    )
                rows = listing.items.map { FileRow.Entry(it) }
                state = listing.state
            }
            else -> {
                val counts = runCatching { media.categoryCounts() }.getOrDefault(emptyMap())
                // Every storage volume is offered as a browsable folder, so an
                // SD card is reachable instead of being silently invisible.
                val volumes = FileBrowser.storageRoots(context)
                    .filterNot { it.absolutePath == Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS)?.absolutePath }
                    .map { FileRow.Entry(FileBrowser.asItem(it)) }
                // The libraries come before the volumes: they are the usual
                // reason for opening this tab, and a file manager that cannot
                // reach your photos sends you looking somewhere else.
                rows = libraryCards() +
                    volumes +
                    SmartCategory.values().map { FileRow.Category(it, counts[it] ?: 0) }
                state = if (rows.isEmpty()) DirectoryState.EMPTY else DirectoryState.OK
            }
        }

        _state.postValue(state)
        _rows.postValue(rows)
        _gallery.postValue(activeLibrary != null)
        _breadcrumbs.postValue(breadcrumbFor(currentPath))
    }

    // ------------------------------------------------------------- libraries

    /**
     * A card per library. The cover is the newest item in it, so the tile shows
     * an actual photo rather than a generic icon.
     */
    private suspend fun libraryCards(): List<FileRow.Library> =
        MediaLibrary.values().map { library ->
            val items = runCatching { libraryItems(library, limit = 1) }.getOrDefault(emptyList())
            val total = runCatching { libraryItems(library).size }.getOrDefault(0)
            FileRow.Library(library, total, items.firstOrNull())
        }

    /** The contents of one library, newest first. */
    private suspend fun libraryItems(
        library: MediaLibrary,
        limit: Int = Int.MAX_VALUE,
    ): List<FileItem> {
        val items = when (library) {
            MediaLibrary.PHOTOS -> media.photos(SortOrder.DATE)
            MediaLibrary.VIDEOS -> media.videos(SortOrder.DATE)
            MediaLibrary.MUSIC -> media.music(SortOrder.DATE)
            MediaLibrary.APPS -> return media.apps().map { it.toFileItem() }
        }
        return items.take(limit).map { it.toFileItem() }
    }

    private fun MediaItem.toFileItem(): FileItem = FileItem(
        path = path ?: uri.toString(),
        name = displayName,
        isDirectory = false,
        sizeBytes = sizeBytes,
        lastModified = dateModified,
        mimeType = mimeType,
        uri = uri,
    )

    private fun AppItem.toFileItem(): FileItem = FileItem(
        path = apkPath,
        name = label,
        isDirectory = false,
        sizeBytes = sizeBytes,
        lastModified = 0L,
        mimeType = "application/vnd.android.package-archive",
    )

    fun openLibrary(library: MediaLibrary) {
        activeLibrary = library
        activeCategory = null
        currentPath = ""
        refresh()
    }

    /**
     * The root segment is *always* present, whichever route got us here. It was
     * previously omitted when navigation started below the storage root.
     */
    private fun breadcrumbFor(path: String): List<Breadcrumb> {
        if (path.isBlank()) {
            val library = activeLibrary
            if (library != null) {
                return listOf(Breadcrumb(ROOT_LABEL, ""), Breadcrumb(library.label(), ""))
            }
            val category = activeCategory
            return if (category == null) listOf(Breadcrumb(ROOT_LABEL, ""))
            else listOf(Breadcrumb(ROOT_LABEL, ""), Breadcrumb(category.label(), ""))
        }
        // Measure against whichever volume the path lives on, so browsing an
        // SD card shows "SD card" instead of a raw volume id like ABCD-1234.
        val base = FileBrowser.storageRoots(context)
            .firstOrNull { path == it.absolutePath || path.startsWith(it.absolutePath + "/") }
            ?: File(rootPath)
        val list = mutableListOf(
            Breadcrumb(ROOT_LABEL, ""),
            Breadcrumb(FileBrowser.volumeLabel(base.absolutePath), base.absolutePath),
        )
        val relative = path.removePrefix(base.absolutePath).trim('/')
        if (relative.isNotEmpty()) {
            var accumulated = base.absolutePath
            for (segment in relative.split('/')) {
                if (segment.isEmpty()) continue
                accumulated = "$accumulated/$segment"
                list.add(Breadcrumb(segment, accumulated))
            }
        }
        return list
    }

    // ------------------------------------------------------------- navigation

    fun navigateTo(path: String) {
        currentPath = if (path.isBlank() || path == rootPath) "" else path
        activeCategory = null
        activeLibrary = null
        refresh()
    }

    /** Back to the top level; false when already there. */
    fun navigateUp(): Boolean {
        if (activeLibrary == null && activeCategory == null && currentPath.isBlank()) return false
        activeLibrary = null
        activeCategory = null
        currentPath = ""
        refresh()
        return true
    }

    /** Says where the list currently is, for the screen to hint at. */
    fun openedLabel(): String? = when {
        activeLibrary != null -> activeLibrary!!.label()
        activeCategory != null -> activeCategory!!.label()
        else -> null
    }

    /** Folders living alongside the segment at [depth], for the address-bar caret. */
    suspend fun siblingsAt(depth: Int): List<Breadcrumb> {
        val crumbs = _breadcrumbs.value ?: return emptyList()
        val target = crumbs.getOrNull(depth) ?: return emptyList()
        if (target.path.isBlank()) return emptyList()
        val parentPath = target.path.substringBeforeLast('/').ifBlank { rootPath }
        return runCatching {
            media.listDirectory(parentPath).items
                .filter { it.isDirectory }
                .map { Breadcrumb(it.name, it.path) }
        }.getOrDefault(emptyList())
    }

    fun onItemClick(row: FileRow) {
        when (row) {
            is FileRow.Library -> openLibrary(row.library)
            is FileRow.Category -> {
                activeCategory = row.category
                activeLibrary = null
                currentPath = ""
                viewModelScope.launch { loadRows() }
            }
            is FileRow.Entry -> {
                val item = row.item
                if (item.isDirectory) {
                    if (!item.canRead) {
                        _state.value = DirectoryState.RESTRICTED
                        return
                    }
                    currentPath = item.path
                    activeCategory = null
                    activeLibrary = null
                    viewModelScope.launch { loadRows() }
                } else {
                    toggleSelection(item)
                    _rows.postValue(_rows.value)
                }
            }
        }
    }

    // -------------------------------------------------------------- selection

    fun toggleSelection(item: FileItem) {
        if (selection.containsKey(item.path)) selection.remove(item.path)
        else selection[item.path] = item
    }

    fun isSelected(item: FileItem): Boolean = selection.containsKey(item.path)
    fun selectedItems(): List<FileItem> = selection.values.toList()
    fun selectionSize(): Int = selection.size
    fun clearSelection() = selection.clear()

    fun currentPath(): String = currentPath.ifBlank { rootPath }

    // ---------------------------------------------------------------- actions

    fun delete(items: List<FileItem>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            var removed = 0
            items.forEach { item ->
                runCatching { fileOps.delete(item) }.onSuccess { removed++ }
            }
            loadRows()
            onDone(removed)
        }
    }

    fun rename(item: FileItem, newName: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { fileOps.rename(File(item.path), newName) }
            loadRows()
            onDone()
        }
    }

    fun moveOrCopy(items: List<FileItem>, target: String, move: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            val directory = File(target)
            items.forEach { item ->
                runCatching { if (move) fileOps.move(item, directory) else fileOps.copy(item, directory) }
            }
            loadRows()
            onDone()
        }
    }

    fun compress(items: List<FileItem>, onDone: (File) -> Unit) {
        viewModelScope.launch {
            val zip = File(currentPath().ifBlank { items.firstOrNull()?.path?.substringBeforeLast('/') }, "morselink.zip")
            runCatching { fileOps.compress(items, zip) }
            loadRows()
            onDone(zip)
        }
    }

    fun sizeLabel(item: FileItem): String =
        if (item.isDirectory) {
            if (item.childCount >= 0) "${item.childCount} items" else "Folder"
        } else Format.bytes(item.sizeBytes)

    private companion object {
        const val ROOT_LABEL = "Internal Storage"

        private fun SmartCategory.label(): String = when (this) {
            SmartCategory.DOCUMENTS -> "Documents"
            SmartCategory.EBOOKS -> "Ebooks"
            SmartCategory.APKS -> "APKs"
            SmartCategory.ARCHIVES -> "Archives"
            SmartCategory.LARGE_FILES -> "Large files"
        }
    }
}
