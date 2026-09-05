package com.morselink.feature.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morselink.core.media.MediaRepository
import com.morselink.core.media.SmartCategory
import com.morselink.core.media.SortOrder
import com.morselink.core.ui.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SendViewModel @Inject constructor(
    private val media: MediaRepository,
    val selection: SendSelection,
    private val args: SendArgs,
) : ViewModel() {

    /** The single source of truth: the tab label and the dataset both read this. */
    private val _tab = MutableLiveData(SendTab.PHOTOS)
    val tab: LiveData<SendTab> = _tab

    private val _sort = MutableLiveData(SortOrder.DATE)
    val sort: LiveData<SortOrder> = _sort

    private val _rows = MutableLiveData<List<SendRow>>(emptyList())
    val rows: LiveData<List<SendRow>> = _rows

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private var query: String = ""
    private var activeCategory: SmartCategory? = null
    private var flatRows: List<SendRow> = emptyList()

    private var loadJob: Job? = null

    init {
        setTab(SendTab.PHOTOS)
    }

    fun consumeExternalFiles() {
        if (args.externalUris.isNotEmpty()) {
            selection.addExternal(args.externalUris)
            args.externalUris = emptyList()
        }
    }

    fun setTab(value: SendTab) {
        _tab.value = value
        activeCategory = null
        // Drop the previous tab's rows straight away. Previously the old dataset
        // stayed on screen while the new tab's data loaded, so the active tab
        // label and the visible content disagreed.
        flatRows = emptyList()
        _rows.value = emptyList()
        reload()
    }

    fun setSort(value: SortOrder) {
        if (_sort.value == value) return
        _sort.value = value
        reload()
    }

    fun currentSort(): SortOrder = _sort.value ?: SortOrder.DATE

    fun setQuery(value: String) {
        query = value
        emit()
    }

    fun openCategory(category: SmartCategory) {
        activeCategory = category
        reload()
    }

    fun toggle(row: SendRow) {
        when (row) {
            is SendRow.Header -> toggleGroup(row)
            else -> selection.toggle(row)
        }
        emit()
    }

    /** The per-date-group select-all toggle (§14.2). */
    private fun toggleGroup(header: SendRow.Header) {
        val items = visibleRows().filter { it.groupKey == header.group }
        if (items.isEmpty()) return
        val allSelected = items.all { selection.isSelected(it) }
        items.forEach { item ->
            if (selection.isSelected(item) == allSelected) selection.toggle(item)
        }
    }

    fun isSelected(row: SendRow): Boolean = selection.isSelected(row)

    fun clearSelection() {
        selection.clear()
        emit()
    }

    fun selectedBytes(): Long = selection.snapshot().sumOf { it.sizeBytes }

    private fun visibleRows(): List<SendRow> =
        if (query.isBlank()) flatRows
        else flatRows.filter { it.name.contains(query, ignoreCase = true) }

    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.postValue(true)
            val tabValue = _tab.value ?: SendTab.PHOTOS
            val sortValue = _sort.value ?: SortOrder.DATE
            val loaded: List<SendRow> = when (tabValue) {
                SendTab.PHOTOS -> media.photos(sortValue).map { SendRow.Media(it) }
                SendTab.VIDEOS -> media.videos(sortValue).map { SendRow.Media(it) }
                SendTab.MUSIC -> media.music(sortValue).map { SendRow.Media(it) }
                SendTab.APPS -> media.apps().map { SendRow.App(it) }
                SendTab.FILES -> {
                    val category = activeCategory
                    if (category == null) {
                        val counts = runCatching { media.categoryCounts() }.getOrDefault(emptyMap())
                        SmartCategory.values().map { SendRow.Category(it, counts[it] ?: 0) }
                    } else {
                        media.category(category).map { SendRow.File(it) }
                    }
                }
            }
            flatRows = sortRows(loaded, sortValue)
            _loading.postValue(false)
            emit()
        }
    }

    /**
     * Some OEM builds ignore MediaStore's ORDER BY, so the ordering is applied
     * here as well. Sorting is what the user asked for; it should not depend on
     * the vendor honouring the query.
     */
    private fun sortRows(rows: List<SendRow>, sort: SortOrder): List<SendRow> {
        if (rows.isEmpty() || rows.first() is SendRow.Category) return rows
        return when (sort) {
            SortOrder.DATE -> rows.sortedByDescending { it.timestamp }
            SortOrder.SIZE -> rows.sortedByDescending { it.sizeBytes }
            SortOrder.NAME -> rows.sortedBy { it.name.lowercase() }
        }
    }

    private fun emit() {
        _rows.postValue(withHeaders(visibleRows()))
    }

    /** Inserts a selectable date header before each group. */
    private fun withHeaders(source: List<SendRow>): List<SendRow> {
        if (source.isEmpty()) return emptyList()
        if (source.first() is SendRow.Category) return source

        val out = mutableListOf<SendRow>()
        var currentKey: String? = null
        var bucket = mutableListOf<SendRow>()

        fun flush() {
            val rows = bucket
            if (rows.isNotEmpty()) {
                val first = rows.first()
                out.add(
                    SendRow.Header(
                        label = if (first.timestamp > 0) Format.dayLabel(first.timestamp) else first.name,
                        group = first.groupKey,
                        count = rows.size,
                        allSelected = rows.all { selection.isSelected(it) },
                    )
                )
                out.addAll(rows)
            }
            bucket = mutableListOf()
        }

        for (row in source) {
            if (row.groupKey != currentKey) {
                flush()
                currentKey = row.groupKey
            }
            bucket.add(row)
        }
        flush()
        return out
    }
}
