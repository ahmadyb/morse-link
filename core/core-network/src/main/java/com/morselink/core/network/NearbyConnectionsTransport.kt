package com.morselink.core.network

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Tasks
import com.morselink.core.transfer.engine.TransferEngine
import com.morselink.core.transfer.model.IncomingFileEvent
import com.morselink.core.transfer.model.TransferDirection
import com.morselink.core.transfer.model.TransferProgress
import com.morselink.core.transfer.model.TransferStatus
import com.morselink.core.transfer.model.TransferableFile
import com.morselink.core.transfer.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * §4.3 — primary transport on GMS devices.
 * BLE discovery, automatic escalation to Wi-Fi Direct / LAN for payloads, and
 * Payload.fromFile() so the SDK owns chunking, sequencing and confirmation.
 */
@Singleton
class NearbyConnectionsTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TransferEngine,
) : TransportProvider {

    override val id: TransportType = TransportType.NEARBY_CONNECTIONS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val incomingFiles = ConcurrentHashMap<Long, File>()

    override fun isAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun startDiscovery(): Flow<DiscoveredPeer> = callbackFlow {
        val callback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                trySend(
                    DiscoveredPeer(
                        id = endpointId,
                        name = info.endpointName,
                        transport = TransportType.NEARBY_CONNECTIONS,
                    )
                )
            }

            override fun onEndpointLost(endpointId: String) = Unit
        }
        client.startDiscovery(
            SERVICE_ID,
            callback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnFailureListener { close(it) }
        awaitClose { runCatching { client.stopDiscovery() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun startAdvertising(localName: String): Flow<DiscoveredPeer> = callbackFlow {
        val callback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                client.acceptConnection(endpointId, payloadCallback())
                    .addOnFailureListener { error -> close(error) }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                    trySend(DiscoveredPeer(endpointId, localName, id))
                }
            }

            override fun onDisconnected(endpointId: String) = Unit
        }
        client.startAdvertising(
            localName,
            SERVICE_ID,
            callback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnFailureListener { close(it) }
        awaitClose { runCatching { client.stopAdvertising() } }
    }

    override suspend fun stopAdvertising() {
        runCatching { client.stopAdvertising() }
    }

    override suspend fun stopDiscovery() {
        runCatching { client.stopDiscovery() }
    }

    override suspend fun connect(peer: DiscoveredPeer): TransportSession {
        suspendCancellableCoroutine<Boolean> { continuation ->
            val resumed = AtomicBoolean(false)
            val callback = object : ConnectionLifecycleCallback() {
                override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
                    client.acceptConnection(id, payloadCallback())
                        .addOnFailureListener { error ->
                            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                        }
                }

                override fun onConnectionResult(id: String, resolution: ConnectionResolution) {
                    if (!resumed.compareAndSet(false, true) || !continuation.isActive) return
                    if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                        continuation.resume(true)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("Connection rejected (${resolution.status.statusCode})")
                        )
                    }
                }

                override fun onDisconnected(id: String) = Unit
            }
            client.requestConnection(peer.name, peer.id, callback)
                .addOnFailureListener { error ->
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
        }
        return NearbySession(peer)
    }

    private fun payloadCallback(): PayloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.FILE) return
            val staged = payload.asFile()?.asJavaFile() ?: return
            incomingFiles[payload.id] = staged
            engine.begin(
                TransferableFile(
                    id = payload.id.toString(),
                    name = staged.name,
                    sizeBytes = staged.length(),
                    mimeType = null,
                    path = staged.absolutePath,
                ),
                TransferDirection.INCOMING,
                TransportType.NEARBY_CONNECTIONS,
            )
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    engine.update(update.payloadId.toString(), update.bytesTransferred)

                PayloadTransferUpdate.Status.SUCCESS -> {
                    val staged = incomingFiles.remove(update.payloadId) ?: return
                    scope.launch {
                        val directory = defaultDownloadDirectory(context)
                        val target = File(directory, staged.name)
                        runCatching { staged.copyTo(target, overwrite = true); staged.delete() }
                        engine.complete(
                            fileId = update.payloadId.toString(),
                            localPath = target.absolutePath,
                            peerName = endpointId,
                            publishToMediaStore = true,
                        )
                    }
                }

                PayloadTransferUpdate.Status.FAILURE -> scope.launch {
                    engine.fail(update.payloadId.toString(), "Nearby payload transfer failed")
                }
            }
        }
    }

    private inner class NearbySession(
        override val peer: DiscoveredPeer,
    ) : TransportSession {

        override suspend fun sendFile(file: TransferableFile): Flow<TransferProgress> = flow {
            val source = File(requireNotNull(file.path) { "Nearby Connections needs a file path" })
            val id = engine.begin(file, TransferDirection.OUTGOING, TransportType.NEARBY_CONNECTIONS)
            withContext(Dispatchers.IO) {
                Tasks.await(client.sendPayload(peer.id, Payload.fromFile(source)))
            }
            var last = -1L
            while (true) {
                val progress = engine.progressFor(id) ?: break
                if (progress.bytesTransferred != last) {
                    last = progress.bytesTransferred
                    emit(progress)
                }
                if (progress.status == TransferStatus.COMPLETED ||
                    progress.status == TransferStatus.FAILED ||
                    progress.status == TransferStatus.CANCELLED
                ) break
                delay(150)
            }
        }

        override fun incomingFiles(): Flow<IncomingFileEvent> = engine.events

        override suspend fun close() {
            runCatching { client.disconnectFromEndpoint(peer.id) }
        }
    }

    companion object {
        const val SERVICE_ID = "com.morselink.app.nearby"
    }
}
