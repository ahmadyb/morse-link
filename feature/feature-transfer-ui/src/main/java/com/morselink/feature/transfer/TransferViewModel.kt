package com.morselink.feature.transfer

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.morselink.core.data.prefs.SettingsStore
import com.morselink.core.network.ConnectionHolder
import com.morselink.core.network.NetworkUtils
import com.morselink.core.network.PairingPayload
import com.morselink.core.network.SessionServiceController
import com.morselink.core.network.TransportSelector
import com.morselink.core.transfer.engine.TransferEngine
import com.morselink.core.transfer.legacy.LegacyPorts
import com.morselink.core.transfer.model.TransferDirection
import com.morselink.core.transfer.model.TransferSessionState
import com.morselink.core.transfer.model.label
import com.morselink.core.ui.Format
import com.morselink.core.ui.QrCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the pairing panel shows while we wait for a receiver (§14.8). */
data class PairingState(
    val qr: Bitmap? = null,
    val address: String? = null,
    val status: String = "",
    val visible: Boolean = false,
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val engine: TransferEngine,
    private val holder: ConnectionHolder,
    private val selector: TransportSelector,
    private val network: NetworkUtils,
    private val settings: SettingsStore,
    private val service: SessionServiceController,
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

    private val _pairing = MutableLiveData(PairingState())
    val pairing: LiveData<PairingState> = _pairing

    private var advertiseJob: Job? = null

    init {
        // If the Send flow queued files, we are the sender: start listening and
        // publish the QR the receiver has to scan.
        if (holder.pendingOutgoing.isNotEmpty() && holder.session == null) {
            startSenderPairing()
        } else {
            service.start()
        }
    }

    private fun startSenderPairing() {
        service.start()
        advertiseJob = viewModelScope.launch {
            val name = runCatching { settings.current().deviceName }
                .getOrDefault("Morselink")
                .ifBlank { "Morselink" }

            // Listen in the background; the flow emits when a receiver joins.
            launch {
                runCatching {
                    selector.advertise(name) { peer ->
                        holder.peer = peer
                        viewModelScope.launch {
                            runCatching { holder.session = selector.connect(peer) }
                                .onSuccess { sendPending() }
                        }
                    }
                }
            }

            val address = withContext(Dispatchers.IO) {
                runCatching { network.localIpAddress() }.getOrNull()
            }
            _pairing.postValue(
                if (address.isNullOrBlank()) {
                    PairingState(
                        address = null,
                        status = "Connect both phones to the same Wi-Fi or hotspot, then reopen this screen.",
                        visible = true,
                    )
                } else {
                    val payload = PairingPayload(
                        name = name,
                        address = address,
                        port = LegacyPorts.CONTROL_PORT,
                        transport = selector.primary().id.name,
                    )
                    PairingState(
                        qr = QrCode.bitmap(payload.toJson()),
                        address = address,
                        status = "Show this code to the receiver",
                        visible = true,
                    )
                }
            )
        }
    }

    /** Files queued by the Send flow, sent once a session exists. */
    private suspend fun sendPending() {
        val session = holder.session ?: return
        val transport = holder.peer?.transport
            ?: com.morselink.core.transfer.model.TransportType.LEGACY_WIFI_DIRECT
        val files = holder.pendingOutgoing
        _pairing.postValue(PairingState(visible = false))
        files.forEach { file ->
            try {
                val id = engine.begin(file, TransferDirection.OUTGOING, transport)
                session.sendFile(file).collect { progress ->
                    engine.update(progress.fileId.ifBlank { id }, progress.bytesTransferred)
                }
            } catch (error: Throwable) {
                engine.fail(file.id, error.message ?: "Send failed")
            }
        }
        holder.pendingOutgoing = emptyList()
    }

    fun cancelAll() {
        advertiseJob?.cancel()
        viewModelScope.launch {
            engine.cancelAll()
            holder.close()
            service.stop()
        }
    }

    override fun onCleared() {
        advertiseJob?.cancel()
        super.onCleared()
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
