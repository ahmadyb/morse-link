package com.morselink.feature.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.morselink.core.media.MediaRepository
import com.morselink.core.media.SmartCategory
import com.morselink.core.media.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SendViewModel @Inject constructor(
    private val media: MediaRepository,
    val selection: SendSelection,
    private val args: SendArgs,
) : ViewModel() {

    var tab: SendTab = SendTab.PHOTOS
        private set

    private var sort: SortOrder = SortOrder.DATE
    private var query: String = ""
    private var activeCategory: SmartCategory? = null
    private var allRows: List<SendRow> = emptyList()

    private val _rows = MutableLiveData<List<SendRow>>(emptyList())
    val rows: LiveData<List<SendRow>> = _rows

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

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
        tab = value
        activeCategory = null
        reload()
    }

    fun setSort(value: SortOrder) {
        sort = value
        reload()
    }

    fun setQuery(value: String) {
        query = value
        applyFilter()
    }

    fun openCategory(category: SmartCategory) {
        activeCategory = category
        reload()
    }

    fun toggle(row: SendRow) {
        selection.toggle(row)
    }

    fun isSelected(row: SendRow): Boolean = selection.isSelected(row)

    fun clearSelection() = selection.clear()

    fun selectedBytes(): Long = selection.snapshot().sumOf { it.sizeBytes }

    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.postValue(true)
            val rows: List<SendRow> = when (tab) {
                SendTab.PHOTOS -> media.photos(sort).map { SendRow.Media(it) }
                SendTab.VIDEOS -> media.videos(sort).map { SendRow.Media(it) }
                SendTab.MUSIC -> media.music(sort).map { SendRow.Media(it) }
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
            allRows = rows
            _loading.postValue(false)
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = if (query.isBlank()) allRows else allRows.filter { row ->
            row.name.contains(query, ignoreCase = true)
        }
        _rows.postValue(filtered)
    }
}
