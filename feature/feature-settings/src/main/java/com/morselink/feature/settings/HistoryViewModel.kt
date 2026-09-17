package com.morselink.feature.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.morselink.core.data.TransferHistoryRepository
import com.morselink.core.data.db.TransferEntity
import com.morselink.core.ui.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: TransferHistoryRepository,
) : ViewModel() {

    // A MutableLiveData did not belong here. The rows are a flow over the
    // database, so changing _direction re-emitted nothing: switching to Sent
    // left the Received list on screen until something wrote to the database
    // again, which is why both tabs showed the same entries. The direction has
    // to be part of the flow for the filter to re-run.
    private val _direction = MutableStateFlow(TransferEntity.DIRECTION_RECEIVED)
    val direction: LiveData<String> = _direction.asLiveData()

    val rows: LiveData<List<HistoryRow>> = combine(
        history.observeAll(),
        _direction,
    ) { list, wanted ->
        val rows = mutableListOf<HistoryRow>()
        var lastDay = -1
        for (item in list.filter { it.direction == wanted }) {
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
    }

    private fun dayOf(timestamp: Long): Int =
        (timestamp / (24L * 60 * 60 * 1000)).toInt()
}
