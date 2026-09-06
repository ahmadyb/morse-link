package com.morselink.feature.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.morselink.core.data.TransferHistoryRepository
import com.morselink.core.data.db.TransferEntity
import com.morselink.core.ui.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: TransferHistoryRepository,
) : ViewModel() {

    private val _direction = MutableLiveData(TransferEntity.DIRECTION_RECEIVED)
    val direction: LiveData<String> = _direction

    val rows: LiveData<List<HistoryRow>> = history.observeAll().map { list ->
        val wanted = _direction.value ?: TransferEntity.DIRECTION_RECEIVED
        val filtered = list.filter { it.direction == wanted }
        val rows = mutableListOf<HistoryRow>()
        var lastDay = -1
        for (item in filtered) {
            val dayKey = dayOf(item.timestamp)
            if (dayKey != lastDay) {
                rows.add(HistoryRow.Header(Format.dayLabel(item.timestamp)))
                lastDay = dayKey
            }
            rows.add(HistoryRow.Entry(item))
        }
        rows
    }.asLiveData()

    fun setDirection(value: String) {
        _direction.value = value
        _direction.postValue(value) // re-trigger the flow with the new filter
    }

    private fun dayOf(timestamp: Long): Int =
        (timestamp / (24L * 60 * 60 * 1000)).toInt()
}
