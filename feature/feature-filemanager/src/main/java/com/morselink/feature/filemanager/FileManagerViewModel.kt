package com.morselink.feature.filemanager

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val media: MediaRepository,
    private val fileOps: FileOps,
) : ViewModel() {

    private val _rows = MutableLiveData<List<FileRow>>(emptyList())
    val rows: LiveData<List<FileRow>> = _rows

    private val _breadcrumb = MutableLiveData("Internal Storage")
    val breadcrumb: LiveData<String> = _breadcrumb

    private val _storage = MutableLiveData(StorageInfo(0L, 0L))
    val storage: LiveData<StorageInfo> = _storage

    private var currentPath: String = ""
    private var activeCategory: SmartCategory? = null
    private val selection = LinkedHashMap<String, FileItem>()

    fun refresh() {
        viewModelScope.launch {
            _storage.postValue(runCatching { media.storageInfo() }.getOrDefault(StorageInfo(0L, 0L)))
            loadRows()
        }
    }

    private suspend fun loadRows() {
        val rows: List<FileRow> = when {
            currentPath.isNotBlank() ->
                media.listDirectory(currentPath).map { FileRow.Entry(it) }
            activeCategory != null ->
                media.category(activeCategory!!).map { FileRow.Entry(it) }
            else -> {
                val counts = runCatching { media.categoryCounts() }.getOrDefault(emptyMap())
                SmartCategory.values().map { FileRow.Category(it, counts[it] ?: 0) }
            }
        }
        _rows.postValue(rows)
        _breadcrumb.postValue(
            when {
                currentPath.isNotBlank() -> "Internal Storage" +
                    currentPath.replace(Regex("^/storage/emulated/0|^/sdcard"), "").replace("/", "  /  ")
                activeCategory != null -> "Internal Storage  /  ${activeCategory!!.name.lowercase()}"
                else -> "Internal Storage"
            }
        )
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

    fun toggleSelection(item: FileItem) {
        if (selection.containsKey(item.path)) selection.remove(item.path)
        else selection[item.path] = item
    }

    fun isSelected(item: FileItem): Boolean = selection.containsKey(item.path)

    fun selectedItems(): List<FileItem> = selection.values.toList()

    fun selectionSize(): Int = selection.size

    fun clearSelection() = selection.clear()

    fun currentPath(): String = currentPath.ifBlank {
        runCatching {
            com.morselink.core.media.FileBrowser.storageRoots().firstOrNull()?.absolutePath
        }.getOrDefault("") ?: ""
    }

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
        if (item.isDirectory) "${item.childCount} items" else Format.bytes(item.sizeBytes)
}
