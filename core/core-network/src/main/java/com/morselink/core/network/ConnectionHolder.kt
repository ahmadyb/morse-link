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

    fun hasSession(): Boolean = session != null

    suspend fun close() {
        runCatching { session?.close() }
        session = null
        peer = null
    }
}
