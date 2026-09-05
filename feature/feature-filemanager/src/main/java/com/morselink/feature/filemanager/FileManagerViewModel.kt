package com.morselink.feature.filemanager

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morselink.core.media.DirectoryState
import com.morselink.core.media.FileBrowser
import com.morselink.core.media.FileItem
import com.morselink.core.media.FileOps
import com.morselink.core.media.MediaRepository
import com.morselink.core.media.SmartCategory
import com.morselink.core.media.StorageInfo
import com.morselink.core.ui.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class Breadcrumb(val label: String, val path: String)

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val media: MediaRepository,
    private val fileOps: FileOps,
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
        FileBrowser.storageRoots().firstOrNull()?.absolutePath
    }.getOrDefault("/storage/emulated/0") ?: "/storage/emulated/0"

    /** Empty means "the smart-category overview", which is the entry state. */
    private var currentPath: String = ""
    private var activeCategory: SmartCategory? = null

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
                rows = SmartCategory.values().map { FileRow.Category(it, counts[it] ?: 0) }
                state = if (rows.isEmpty()) DirectoryState.EMPTY else DirectoryState.OK
            }
        }

        _state.postValue(state)
        _rows.postValue(rows)
        _breadcrumbs.postValue(breadcrumbFor(currentPath))
    }

    /**
     * The root segment is *always* present, whichever route got us here. It was
     * previously omitted when navigation started below the storage root.
     */
    private fun breadcrumbFor(path: String): List<Breadcrumb> {
        if (path.isBlank()) {
            val category = activeCategory
            return if (category == null) listOf(Breadcrumb(ROOT_LABEL, ""))
            else listOf(Breadcrumb(ROOT_LABEL, ""), Breadcrumb(category.label(), ""))
        }
        val relative = path.removePrefix(rootPath).trim('/')
        val list = mutableListOf(Breadcrumb(ROOT_LABEL, ""))
        if (relative.isNotEmpty()) {
            var accumulated = rootPath
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
        refresh()
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
            is FileRow.Category -> {
                activeCategory = row.category
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
