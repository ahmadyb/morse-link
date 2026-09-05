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
import com.morselink.core.transfer.model.TransportType
import com.morselink.core.transfer.model.TransferSessionState
import com.morselink.core.transfer.model.label
import com.morselink.core.ui.Format
import com.morselink.core.ui.QrCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    val port: Int? = null,
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

    /**
     * One line that always says what is going on, so the screen never sits on a
     * bare "waiting for a connection" while the user wonders whether the scan
     * did anything.
     */
    private val _statusLine = MutableLiveData("Waiting for a connection")
    val statusLine: LiveData<String> = _statusLine

    private val _pairing = MutableLiveData(PairingState())
    val pairing: LiveData<PairingState> = _pairing

    /** Set once cancel has finished, so the screen can take the user back. */
    private val _dismiss = MutableLiveData(false)
    val dismiss: LiveData<Boolean> = _dismiss

    private var advertiseJob: Job? = null
    private var sendJob: Job? = null

    init {
        viewModelScope.launch { engine.state.collect { pushStatus() } }

        if (holder.pendingOutgoing.isNotEmpty() && holder.session == null) {
            startSenderPairing()
        } else {
            service.start()
            // Arriving as the receiver: the session is already up, so say who
            // we are connected to instead of implying nothing happened.
            val peer = holder.peer
            if (peer != null && holder.hasSession()) {
                _statusLine.postValue("Connected to ${peer.name} — waiting for files")
            }
        }
    }

    private fun pushStatus() {
        val state = engine.state.value
        val active = state.all().filter { !it.isFinished }
        val peer = holder.peer
        _statusLine.postValue(
            when {
                active.isNotEmpty() -> {
                    val transport = active.first().transport
                    "Transferring ${active.size} file(s)" +
                        (transport?.let { " via ${it.label()}" } ?: "")
                }
                holder.hasSession() && peer != null ->
                    "Connected to ${peer.name} — waiting for files"
                holder.pendingOutgoing.isNotEmpty() ->
                    "Waiting for a receiver to scan the code"
                else -> "Waiting for a connection"
            }
        )
    }

    private fun startSenderPairing() {
        service.start()
        pushStatus()
        advertiseJob = viewModelScope.launch {
            val name = runCatching { settings.current().deviceName }
                .getOrDefault("Morselink")
                .ifBlank { "Morselink" }

            launch {
                runCatching {
                    selector.advertise(name) { peer ->
                        holder.peer = peer
                        sendJob = viewModelScope.launch {
                            runCatching { holder.session = selector.connect(peer) }
                                .onSuccess {
                                    _statusLine.postValue("Connected to ${peer.name}")
                                    sendPending()
                                }
                                .onFailure { error ->
                                    _statusLine.postValue(
                                        "Could not connect: ${error.message ?: "unknown error"}"
                                    )
                                }
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
                    val port = LegacyPorts.CONTROL_PORT
                    val payload = PairingPayload(
                        name = name,
                        address = address,
                        port = port,
                        transport = selector.primary().id.name,
                    )
                    PairingState(
                        qr = QrCode.bitmap(payload.toJson()),
                        address = address,
                        port = port,
                        status = "Scan this code, or type the address and port below into the receiver.",
                        visible = true,
                    )
                }
            )
        }
    }

    /** Files queued by the Send flow, sent once a session exists. */
    private suspend fun sendPending() {
        val session = holder.session ?: return
        val transport = holder.peer?.transport ?: TransportType.LEGACY_WIFI_DIRECT
        val files = holder.pendingOutgoing
        _pairing.postValue(PairingState(visible = false))
        files.forEach { file ->
            try {
                val id = engine.begin(file, TransferDirection.OUTGOING, transport)
                session.sendFile(file).collect { progress ->
                    engine.update(progress.fileId.ifBlank { id }, progress.bytesTransferred)
                }
            } catch (error: Throwable) {
                // Cancellation is not a failure — let it propagate or cancel
                // would only ever mark files as failed and keep sending.
                if (error is CancellationException) throw error
                engine.fail(file.id, error.message ?: "Send failed")
            }
        }
        holder.pendingOutgoing = emptyList()
    }

    fun cancelAll() {
        advertiseJob?.cancel()
        sendJob?.cancel()
        holder.pendingOutgoing = emptyList()
        viewModelScope.launch {
            engine.cancelAll()
            holder.close()
            service.stop()
            _pairing.postValue(PairingState(visible = false))
            _statusLine.postValue("Cancelled")
            _dismiss.postValue(true)
        }
    }

    override fun onCleared() {
        advertiseJob?.cancel()
        sendJob?.cancel()
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
