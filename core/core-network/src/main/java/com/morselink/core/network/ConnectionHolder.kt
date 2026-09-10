package com.morselink.core.network

import com.morselink.core.transfer.model.TransferableFile
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

    suspend fun close() {
        runCatching { session?.close() }
        session = null
        peer = null
        isSender = false
    }
}
