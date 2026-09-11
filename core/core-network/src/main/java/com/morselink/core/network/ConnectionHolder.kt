package com.morselink.core.network

import com.morselink.core.transfer.model.TransferableFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide handle on the live session, so a transfer started on the Send
 * screen keeps running (and keeps reporting) after the user navigates away.
 */
@Singleton
class ConnectionHolder @Inject constructor() {

    @Volatile
    var session: TransportSession? = null

    @Volatile
    var peer: DiscoveredPeer? = null

    @Volatile
    var pendingOutgoing: List<TransferableFile> = emptyList()

    /**
     * Whether this device is the one pushing files in this session.
     *
     * Both directions share a single control socket: the sender writes file
     * metadata and waits for a resume reply on it, and the receive loop reads
     * that same socket for incoming metadata. Run both and they take each
     * other's lines - the resume reply gets swallowed by the receive loop and
     * the send stalls, which is why some files in a batch finished and some
     * stopped. Remembering the role here, rather than in a ViewModel, is what
     * stops a screen re-created after Minimise from starting the wrong loop.
     */
    @Volatile
    var isSender: Boolean = false

    fun hasSession(): Boolean = session != null

    /**
     * Whether a session is up, as something that can be watched.
     *
     * The session was a plain field, so nothing heard about it going away. A
     * connection that died on its own - the other phone leaving, or Stop being
     * pressed on the notification - left the screen still showing its green
     * "Connected" card and still offering to send, with no session behind it.
     */
    private val _alive = MutableStateFlow(false)
    val alive: StateFlow<Boolean> = _alive.asStateFlow()

    /**
     * Publishes the session and announces it in one step.
     *
     * Deliberately leaves [isSender] alone. The sender decides its role before
     * pairing, long before the receiver dials in, so clearing the flag here
     * silently turned the sender back into a receiver - and a Minimise during
     * a send then started a receive loop on top of the send that was still
     * running.
     */
    fun attach(session: TransportSession, peer: DiscoveredPeer) {
        this.session = session
        this.peer = peer
        _alive.value = true
    }

    suspend fun close() {
        runCatching { session?.close() }
        session = null
        peer = null
        isSender = false
        _alive.value = false
    }
}
