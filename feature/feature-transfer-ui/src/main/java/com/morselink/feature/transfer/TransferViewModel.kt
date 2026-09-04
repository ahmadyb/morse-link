package com.morselink.feature.transfer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.morselink.core.network.ConnectionHolder
import com.morselink.core.transfer.engine.TransferEngine
import com.morselink.core.transfer.model.TransferSessionState
import com.morselink.core.transfer.model.label
import com.morselink.core.ui.Format
import com.morselink.feature.transfer.databinding.FragmentTransferBinding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val engine: TransferEngine,
    private val holder: ConnectionHolder,
) : ViewModel() {

    val rows: LiveData<List<TransferRow>> = engine.state.map { it.toRows() }.asLiveData()

    val transportLabel: LiveData<String> = engine.state.map { state ->
        val transport = state.all().firstOrNull()?.transport
        if (transport != null) "Transferring — via ${transport.label()}" else "Waiting for a connection"
    }.asLiveData()

    val stats: LiveData<String> = engine.state.map { state ->
        val active = state.all().filter { !it.isFinished }
        if (active.isEmpty()) "No active transfer"
        else {
            val speed = active.sumOf { it.bytesPerSecond }
            val eta = active.map { it.etaSeconds }.filter { it >= 0 }.maxOrNull() ?: -1
            val remaining = active.sumOf { (it.sizeBytes - it.bytesTransferred).coerceAtLeast(0) }
            "${Format.speed(speed)} · ${Format.bytes(remaining)} left" +
                if (eta >= 0) " · ${Format.eta(eta)}" else ""
        }
    }.asLiveData()

    init {
        // Kick off whatever the Send flow queued up.
        val outgoing = holder.pendingOutgoing
        if (outgoing.isNotEmpty()) {
            viewModelScope.launch {
                val session = holder.session
                if (session != null) {
                    outgoing.forEach { file -> session.sendFile(file) }
                }
            }
        }
    }

    fun cancelAll() {
        viewModelScope.launch {
            engine.cancelAll()
            holder.close()
        }
    }

    private fun TransferSessionState.toRows(): List<TransferRow> {
        val rows = mutableListOf<TransferRow>()
        if (outgoing.isNotEmpty()) {
            rows.add(TransferRow.Header("Sending (${outgoing.size})"))
            rows.addAll(outgoing.values.map { TransferRow.Item(it) })
        }
        if (incoming.isNotEmpty()) {
            rows.add(TransferRow.Header("Receiving (${incoming.size})"))
            rows.addAll(incoming.values.map { TransferRow.Item(it) })
        }
        return rows
    }
}
