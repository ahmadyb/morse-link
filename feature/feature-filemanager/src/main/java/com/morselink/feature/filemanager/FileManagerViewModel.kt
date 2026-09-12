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

/**
 * The tabs across the top of the Files screen.
 *
 * The libraries are tabs rather than gallery tiles: they are a way to narrow a
 * list, not a set of destinations, and one list that changes is quicker to read
 * than four grids you have to leave and re-enter.
 */
enum class FilesTab { PHOTOS, VIDEOS, MUSIC, APPS, FILES }

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

    private val _tab = MutableLiveData(FilesTab.FILES)
    val tab: LiveData<FilesTab> = _tab

    private var query: String = ""
    private var sort: SortOrder = SortOrder.DATE

    fun setTab(next: FilesTab) {
        if (_tab.value == next) return
        _tab.postValue(next)
        currentPath = ""
        activeCategory = null
        activeLibrary = libraryFor(next)
        refresh()
    }

    private fun libraryFor(tab: FilesTab): MediaLibrary? = when (tab) {
        FilesTab.FILES -> null
        FilesTab.PHOTOS -> MediaLibrary.PHOTOS
        FilesTab.VIDEOS -> MediaLibrary.VIDEOS
        FilesTab.MUSIC -> MediaLibrary.MUSIC
        FilesTab.APPS -> MediaLibrary.APPS
    }

    private fun tabFor(library: MediaLibrary): FilesTab = when (library) {
        MediaLibrary.PHOTOS -> FilesTab.PHOTOS
        MediaLibrary.VIDEOS -> FilesTab.VIDEOS
        MediaLibrary.MUSIC -> FilesTab.MUSIC
        MediaLibrary.APPS -> FilesTab.APPS
    }

    fun setQuery(next: String) {
        if (query == next) return
        query = next
        refresh()
    }

    fun setSort(next: SortOrder) {
        if (sort == next) return
        sort = next
        refresh()
    }

    fun currentSort(): SortOrder = sort

    /** Re-apply search and sort without rescanning storage. */
    private fun present(rows: List<FileRow>): List<FileRow> {
        val term = query.trim()
        var out = rows
        if (term.isNotEmpty()) {
            out = out.filter { row ->
                when (row) {
                    is FileRow.Entry -> row.item.name.contains(term, ignoreCase = true)
                    is FileRow.Category -> row.category.label().contains(term, ignoreCase = true)
                    is FileRow.Library -> row.library.label().contains(term, ignoreCase = true)
                    is FileRow.Header -> false
                }
            }
        }

        val entries = out.filterIsInstance<FileRow.Entry>()
        if (entries.isEmpty()) return out
        val others = out.filter { it !is FileRow.Entry }

        val ordered = entries.sortedWith(
            when (sort) {
                SortOrder.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.item.name }
                SortOrder.SIZE -> compareByDescending<FileRow.Entry> { it.item.sizeBytes }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.item.name }
                SortOrder.DATE -> compareByDescending<FileRow.Entry> { it.item.lastModified }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.item.name }
            }
        )
        // Folders first only when there are folders, so a directory listing
        // reads as structure rather than as a shuffled heap.
        val withFoldersFirst = if (ordered.any { it.item.isDirectory }) {
            ordered.sortedBy { if (it.item.isDirectory) 0 else 1 }
        } else ordered

        val body: List<FileRow> =
            if (sort == SortOrder.DATE) withDateGroups(withFoldersFirst) else withFoldersFirst
        return if (others.isEmpty()) body else others + body
    }

    /**
     * Today / Yesterday / Earlier headings, so a library of three thousand
     * screenshots is navigable instead of being one undifferentiated column.
     */
    private fun withDateGroups(entries: List<FileRow.Entry>): List<FileRow> {
        val out = mutableListOf<FileRow>()
        var last = Int.MIN_VALUE
        for (entry in entries) {
            val bucket = dateBucket(entry.item.lastModified)
            if (bucket != last) {
                out.add(FileRow.Header(dateLabel(bucket), bucket))
                last = bucket
            }
            out.add(entry)
        }
        return out
    }

    private fun dateBucket(timestamp: Long): Int {
        if (timestamp <= 0L) return 2
        val cal = java.util.Calendar.getInstance()
        val dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val year = cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = timestamp
        return when {
            cal.get(java.util.Calendar.YEAR) == year &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == dayOfYear -> 0
            cal.get(java.util.Calendar.YEAR) == year &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == dayOfYear - 1 -> 1
            else -> 2
        }
    }

    private fun dateLabel(bucket: Int): String = when (bucket) {
        0 -> context.getString(R.string.files_date_today)
        1 -> context.getString(R.string.files_date_yesterday)
        else -> context.getString(R.string.files_date_earlier)
    }

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
        _rows.postValue(present(rows))
        // Rows throughout: the tabs do the separating, and a grid of thumbnails
        // was slower to scan than a list.
        _gallery.postValue(false)
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
        // Keep the tab strip honest: opening Photos from the overview must
        // leave the strip sitting on Photos, or the highlight and the list
        // disagree about where you are.
        _tab.postValue(tabFor(library))
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
        if (currentPath.isBlank()) _tab.postValue(FilesTab.FILES)
        refresh()
    }

    /** Back to the top level; false when already there. */
    fun navigateUp(): Boolean {
        if (activeLibrary == null && activeCategory == null && currentPath.isBlank()) return false
        activeLibrary = null
        activeCategory = null
        currentPath = ""
        _tab.postValue(FilesTab.FILES)
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
            is FileRow.Header -> Unit
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
    /** Total size of the selection, for the Send button. */
    fun selectedBytes(): Long = selection.values.sumOf { it.sizeBytes }
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
