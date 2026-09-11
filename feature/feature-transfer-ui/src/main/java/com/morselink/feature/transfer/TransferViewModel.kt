package com.morselink.feature.transfer

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.morselink.core.data.prefs.SettingsStore
import com.morselink.core.network.ConnectionHolder
import com.morselink.core.network.IncomingTransferCoordinator
import com.morselink.core.network.NetworkUtils
import com.morselink.core.network.OutgoingTransferCoordinator
import com.morselink.core.network.PairingPayload
import com.morselink.core.network.SessionServiceController
import com.morselink.core.network.TransportSelector
import com.morselink.core.transfer.MorselinkLog
import com.morselink.core.transfer.engine.TransferEngine
import com.morselink.core.transfer.legacy.LegacyPorts
import com.morselink.core.transfer.model.TransportType
import com.morselink.core.transfer.model.TransferSessionState
import com.morselink.core.transfer.model.label
import com.morselink.core.ui.Format
import com.morselink.core.ui.QrCode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
    private val incomingCoordinator: IncomingTransferCoordinator,
    private val outgoing: OutgoingTransferCoordinator,
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

    /**
     * Minimise has a destination of its own. Cancel pops the back stack, which
     * is right for giving up; Minimise goes to Send, because the point of it is
     * to go and pick more files.
     */
    private val _minimised = MutableLiveData(false)
    val minimised: LiveData<Boolean> = _minimised

    /** Minimise is only meaningful with a session to return to. */
    private val _canMinimise = MutableLiveData(false)
    val canMinimise: LiveData<Boolean> = _canMinimise

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
        _canMinimise.postValue(false)
    }

    fun togglePairingCollapsed() {
        pairingCollapsed = !pairingCollapsed
        _pairing.postValue(pairingContent.copy(collapsed = pairingCollapsed))
    }

    init {
        viewModelScope.launch { engine.state.collect { pushStatus() } }

        val pending = holder.pendingOutgoing
        val existing = holder.session

        if ((pending.isNotEmpty() || holder.requestSenderPairing) && existing == null) {
            holder.requestSenderPairing = false
            startSenderPairing()
        } else if (pending.isNotEmpty() && existing != null) {
            // Minimised, then more files picked: the session is still up, so
            // send straight over it. Without this branch the screen fell into
            // the receiver path below and the new selection sat in the holder
            // unsent, with nothing on screen saying so.
            isSender = true
            // Works from either end: a receiver that picks files and taps Send
            // stops its own receive loop and takes the channel over.
            viewModelScope.launch {
                takeOverToSend()
                service.start()
                val name = existing.peer.name
                showConnected(name)
                _statusLine.postValue(context.getString(R.string.status_connected, name))
                holder.pendingOutgoing = emptyList()
                outgoing.send(
                    existing,
                    pending,
                    holder.peer?.transport ?: TransportType.LEGACY_WIFI_DIRECT,
                    onFinished = ::startListening,
                )
            }
        } else {
            service.start()
            // Arriving as the receiver: the session is already up, so say who
            // we are connected to instead of implying nothing happened.
            val peer = holder.peer
            if (peer != null && holder.hasSession()) {
                _statusLine.postValue(context.getString(R.string.status_connected_waiting, peer.name))
                showConnected(peer.name)
                // Nothing receives until this is running: the receive loop
                // lives inside the incomingFiles flow, and without a
                // collector the sender just waits for a reply that never
                // comes and then times out.
                // Only the receiving end. The sender writes to this same
                // control socket, and a receive loop reading it would take
                // the resume replies out from under the send.
                if (!holder.isSender) {
                    holder.session?.let { incomingCoordinator.start(it) }
                }
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
        holder.isSender = true
        // A new send is a new session. Without this the screen opened on the
        // previous attempt's rows — files long finished or cancelled — while the
        // status line said it was waiting for a connection.
        engine.clearFinished()
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
            MorselinkLog.d("sender: advertising $address:$port, waiting for a receiver")
            _statusLine.postValue(context.getString(R.string.status_waiting_receiver))
            sendJob = viewModelScope.launch {
                val session = selector.hostDirect(name)
                if (session == null) {
                    MorselinkLog.w("sender: no receiver dialled in")
                    _statusLine.postValue(context.getString(R.string.status_no_receiver))
                    return@launch
                }
                MorselinkLog.d("sender: receiver connected from ${session.peer.name}")
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
        playFeedback(ToneGenerator.TONE_PROP_ACK)
        _canMinimise.postValue(true)
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

    private fun playFeedback(tone: Int) {
        viewModelScope.launch {
            if (!runCatching { settings.current().soundsEnabled }.getOrDefault(true)) return@launch
            val generator = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() ?: return@launch
            try {
                generator.startTone(tone, 140)
                delay(180)
            } finally {
                generator.release()
            }
        }
    }

    /**
     * Hand the channel over to the other phone.
     *
     * The control socket is shared, so whoever has just finished sending has to
     * start listening, otherwise the other end has no way to send anything back
     * and a receiver can never reply - it could only ever sit and wait.
     */
    private fun startListening() {
        val session = holder.session ?: return
        holder.isSender = false
        if (holder.hasSession()) incomingCoordinator.start(session)
    }

    /**
     * Take the channel in order to send. Stops the receive loop first: it reads
     * the same socket the send writes its handshake to, and would swallow the
     * replies.
     */
    private suspend fun takeOverToSend() {
        // Do not start writing until the receiver has fully released the
        // shared control socket. Cancelling without joining caused the next
        // batch to race the old receive coroutine and fail intermittently.
        incomingCoordinator.stopAndJoin()
        holder.isSender = true
    }

    /**
     * Files queued by the Send flow, sent once a session exists.
     *
     * Handed to the process-scoped coordinator rather than run here: this
     * scope dies with the screen, and a send that stops when you look away
     * is what made minimising useless.
     */
    private fun sendPending() {
        val session = holder.session ?: return
        val transport = holder.peer?.transport ?: TransportType.LEGACY_WIFI_DIRECT
        val files = holder.pendingOutgoing
        holder.pendingOutgoing = emptyList()
        if (files.isEmpty()) return
        outgoing.send(session, files, transport, onFinished = ::startListening)
    }

    /**
     * Leave the screen without ending the session, so the user can go and pick
     * more files and send them over the connection that is already up.
     *
     * Deliberately not [cancelAll]: that closes the session, which is the one
     * thing Minimise must not do.
     */
    fun minimise() {
        _dismiss.postValue(true)
        _minimised.postValue(true)
    }

    fun cancelAll() {
        advertiseJob?.cancel()
        sendJob?.cancel()
        incomingCoordinator.stop()
        outgoing.stop()
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
        // Only the advertising coroutine belongs to this screen. Sends and the
        // receive loop are at process scope and must survive, or returning here
        // would find a session that had been quietly killed on the way out.
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
