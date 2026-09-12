package com.morselink.core.network

import android.content.Context
import com.morselink.core.transfer.model.IncomingFileEvent
import com.morselink.core.transfer.model.TransferProgress
import com.morselink.core.transfer.model.TransferableFile
import com.morselink.core.transfer.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class DiscoveredPeer(
    val id: String,
    val name: String,
    val transport: TransportType,
    val address: String? = null,
    val port: Int = 0,
)

/**
 * §4.1 — one abstraction for every medium. Feature modules never touch a
 * concrete transport, so selection logic can evolve without UI changes.
 */
interface TransportProvider {
    val id: TransportType

    fun isAvailable(context: Context): Boolean

    suspend fun startDiscovery(): Flow<DiscoveredPeer>

    suspend fun connect(peer: DiscoveredPeer): TransportSession

    suspend fun stopDiscovery()

    /** Optional: transports that need an advertising/listening role expose it here. */
    suspend fun startAdvertising(localName: String): Flow<DiscoveredPeer> = emptyFlow()

    suspend fun stopAdvertising() {}
}

interface TransportSession {
    val peer: DiscoveredPeer

    suspend fun sendFile(file: TransferableFile): Flow<TransferProgress>

    fun incomingFiles(): Flow<IncomingFileEvent>

    suspend fun close()
}
