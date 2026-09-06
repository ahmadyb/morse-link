package com.morselink.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §4.2 — transport selection. Nearby Connections is primary where Play services
 * exist, the hand-rolled Wi-Fi Direct stack otherwise. Discovery is bounded by a
 * timeout so the UI can offer the WebShare fallback instead of hanging.
 */
@Singleton
class TransportSelector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearby: NearbyConnectionsTransport,
    private val legacy: LegacyWifiDirectTransport,
) {

    fun primary(): TransportProvider = if (nearby.isAvailable(context)) nearby else legacy

    fun providerFor(peer: DiscoveredPeer): TransportProvider =
        if (peer.transport == com.morselink.core.transfer.model.TransportType.NEARBY_CONNECTIONS) nearby else legacy

    /** Emits peers for at most [timeoutMs]; returns false when nothing was found in time. */
    suspend fun discover(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onPeer: suspend (DiscoveredPeer) -> Unit,
    ): Boolean {
        val provider = primary()
        val completed = withTimeoutOrNull(timeoutMs) {
            provider.startDiscovery().collect { onPeer(it) }
            true
        }
        runCatching { provider.stopDiscovery() }
        return completed == true
    }

    suspend fun advertise(localName: String, onPeer: suspend (DiscoveredPeer) -> Unit): Boolean {
        val provider = primary()
        return runCatching {
            provider.startAdvertising(localName).collect { onPeer(it) }
            true
        }.getOrDefault(false)
    }

    suspend fun connect(peer: DiscoveredPeer): TransportSession = providerFor(peer).connect(peer)

    suspend fun stop() {
        runCatching { nearby.stopDiscovery() }
        runCatching { legacy.stopDiscovery() }
        runCatching { nearby.stopAdvertising() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
