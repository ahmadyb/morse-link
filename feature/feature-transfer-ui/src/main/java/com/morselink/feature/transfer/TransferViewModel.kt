package com.morselink.feature.transfer

import android.util.Log

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Which face the collapsible card at the top of the screen is showing. */
enum class PairingMode { HIDDEN, QR, CONNECTED }

/**
 * The collapsible card at the top of the transfer screen (§14.8).
 *
 * It has two faces: the pairing QR while we wait for a receiver, and the peer we
 * are connected to once the session is up — so the receiving phone gets a
 * connected panel too instead of a bare status line. Both faces collapse down to
 * the title row, because the card is tall enough to bury the transfer list.
 */
data class PairingState(
    val mode: PairingMode = PairingMode.HIDDEN,
    val peerName: String? = null,
    val qr: Bitmap? = null,
    val address: String? = null,
    val port: Int? = null,
    val status: String = "",
    val collapsed: Boolean = false,
) {
    val visible: Boolean get() = mode != PairingMode.HIDDEN
}

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val engine: TransferEngine,
    private val holder: ConnectionHolder,
    private val selector: TransportSelector,
    private val network: NetworkUtils,
    private val settings: SettingsStore,
    private val service: SessionServiceController,
    @ApplicationContext private val context: Context,
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
    private val _statusLine = MutableLiveData(context.getString(R.string.status_waiting_connection))
    val statusLine: LiveData<String> = _statusLine

    private val _pairing = MutableLiveData(PairingState())
    val pairing: LiveData<PairingState> = _pairing

    /** Set once cancel has finished, so the screen can take the user back. */
    private val _dismiss = MutableLiveData(false)
    val dismiss: LiveData<Boolean> = _dismiss

    private var advertiseJob: Job? = null
    private var sendJob: Job? = null

    /** The card's content as last requested, without the collapse flag. */
    private var pairingContent = PairingState()
    private var pairingCollapsed = false
    private var isSender = false

    /**
     * Swaps the card to a new face. A change of face re-expands it: someone who
     * collapsed the QR and then had a receiver connect has new information they
     * minimised the card to get to, not something to hide for good.
     */
    private fun showPairing(state: PairingState) {
        if (state.mode != pairingContent.mode) pairingCollapsed = false
        pairingContent = state
        _pairing.postValue(state.copy(collapsed = pairingCollapsed))
    }

    private fun hidePairing() {
        pairingContent = PairingState()
        pairingCollapsed = false
        _pairing.postValue(PairingState())
    }

    fun togglePairingCollapsed() {
        pairingCollapsed = !pairingCollapsed
        _pairing.postValue(pairingContent.copy(collapsed = pairingCollapsed))
    }

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
                _statusLine.postValue(context.getString(R.string.status_connected_waiting, peer.name))
                showConnected(peer.name)
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
                    if (transport == null) {
                        context.getString(R.string.status_transferring, active.size)
                    } else {
                        context.getString(
                            R.string.status_transferring_via,
                            active.size,
                            transport.label(),
                        )
                    }
                }
                holder.hasSession() && peer != null ->
                    context.getString(R.string.status_connected_waiting, peer.name)
                holder.pendingOutgoing.isNotEmpty() ->
                    context.getString(R.string.status_waiting_receiver)
                else -> context.getString(R.string.status_waiting_connection)
            }
        )
    }

    private fun startSenderPairing() {
        isSender = true
        service.start()
        pushStatus()
        advertiseJob = viewModelScope.launch {
            val name = runCatching { settings.current().deviceName }
                .getOrDefault("Morselink")
                .ifBlank { "Morselink" }

            val address = withContext(Dispatchers.IO) {
                runCatching { network.localIpAddress() }.getOrNull()
            }
            if (address.isNullOrBlank()) {
                showPairing(
                    PairingState(
                        mode = PairingMode.QR,
                        address = null,
                        status = context.getString(R.string.transfer_pairing_no_network),
                    )
                )
                return@launch
            }

            val port = LegacyPorts.CONTROL_PORT
            val payload = PairingPayload(
                name = name,
                address = address,
                port = port,
                transport = selector.primary().id.name,
            )
            showPairing(
                PairingState(
                    mode = PairingMode.QR,
                    qr = QrCode.bitmap(payload.toJson()),
                    address = address,
                    port = port,
                    status = context.getString(R.string.pairing_scan_hint),
                )
            )

            // Bind the control port and wait for the receiver that scanned the
            // code. This is plain TCP over whatever network the two phones
            // share, so it does not depend on Wi-Fi Direct group formation.
            Log.d("Morselink", "sender: advertising $address:$port, waiting for a receiver")
            _statusLine.postValue(context.getString(R.string.status_waiting_receiver))
            sendJob = viewModelScope.launch {
                val session = selector.hostDirect(name)
                if (session == null) {
                    Log.w("Morselink", "sender: no receiver dialled in")
                    _statusLine.postValue(context.getString(R.string.status_no_receiver))
                    return@launch
                }
                Log.d("Morselink", "sender: receiver connected from ${session.peer.name}")
                holder.session = session
                holder.peer = session.peer
                showConnected(session.peer.name)
                _statusLine.postValue(
                    context.getString(R.string.status_connected, session.peer.name)
                )
                sendPending()
            }
        }
    }

    /** The connected face of the card, shown on both ends of the transfer. */
    private fun showConnected(peerName: String) {
        showPairing(
            PairingState(
                mode = PairingMode.CONNECTED,
                peerName = peerName,
                status = context.getString(
                    if (isSender) R.string.pairing_connected_sender_hint
                    else R.string.pairing_connected_hint
                ),
            )
        )
    }

    /** Files queued by the Send flow, sent once a session exists. */
    private suspend fun sendPending() {
        val session = holder.session ?: return
        val transport = holder.peer?.transport ?: TransportType.LEGACY_WIFI_DIRECT
        val files = holder.pendingOutgoing
        Log.d("Morselink", "sender: sending ${files.size} file(s)")
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

        // Let the screen go immediately. Teardown used to run first, and a
        // session blocked on a socket meant Cancel just sat there doing
        // nothing, which is what made it look broken.
        hidePairing()
        _statusLine.postValue(context.getString(R.string.status_cancelled))
        _dismiss.postValue(true)

        viewModelScope.launch {
            runCatching {
                withTimeoutOrNull(3_000) { engine.cancelAll() }
                withTimeoutOrNull(3_000) { holder.close() }
                service.stop()
            }
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
