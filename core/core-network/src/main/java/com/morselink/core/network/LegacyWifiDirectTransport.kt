package com.morselink.core.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.morselink.core.transfer.engine.TransferEngine
import com.morselink.core.transfer.legacy.ChunkProtocol
import com.morselink.core.transfer.legacy.LegacyPorts
import com.morselink.core.transfer.model.IncomingFileEvent
import com.morselink.core.transfer.model.TransferDirection
import com.morselink.core.transfer.model.TransferProgress
import com.morselink.core.transfer.model.TransferableFile
import com.morselink.core.transfer.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * §4.4 — fallback transport for non-GMS devices (and API 21+ generally).
 *
 * Everything Nearby Connections does for us is hand-rolled here: discovery via
 * WifiP2pManager, a JSON control channel, a 64KB chunked data channel with
 * CRC32 per chunk, retransmission requests and byte-offset resume.
 */
@Singleton
class LegacyWifiDirectTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TransferEngine,
) : TransportProvider {

    override val id: TransportType = TransportType.LEGACY_WIFI_DIRECT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private fun ensureChannel(): WifiP2pManager.Channel? {
        if (channel == null) {
            channel = manager?.initialize(context, Looper.getMainLooper(), null)
        }
        return channel
    }

    override fun isAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)

    /**
     * Wi-Fi Direct never puts the peer's IP on [android.net.wifi.p2p.WifiP2pDevice] —
     * the group owner address is only handed out through [WifiP2pManager.requestConnectionInfo],
     * and only once the group has actually formed. Best-effort: resolves to null while
     * the group is still negotiating.
     */
    private fun resolveOwnerAddress(
        wifiManager: WifiP2pManager,
        p2pChannel: WifiP2pManager.Channel,
        onResult: (String?) -> Unit,
    ) {
        runCatching {
            wifiManager.requestConnectionInfo(p2pChannel) { info ->
                onResult(info?.groupOwnerAddress?.hostAddress)
            }
        }.onFailure { onResult(null) }
    }

    @Volatile
    private var lastGroupOwner: String? = null

    @Volatile
    private var weAreOwner: Boolean = false

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun startDiscovery(): Flow<DiscoveredPeer> = callbackFlow {
        val wifiManager = manager
        val p2pChannel = ensureChannel()
        if (wifiManager == null || p2pChannel == null) {
            close(IllegalStateException("Wi-Fi Direct is not available on this device"))
            return@callbackFlow
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                        runCatching {
                            wifiManager.requestPeers(p2pChannel) { peers ->
                                peers?.deviceList?.forEach { device ->
                                    trySend(device.toPeer())
                                }
                            }
                        }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        wifiManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = Unit // discovery is best effort
        })

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { wifiManager.stopPeerDiscovery(p2pChannel, null) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun startAdvertising(localName: String): Flow<DiscoveredPeer> = callbackFlow {
        val wifiManager = manager ?: run { close(); return@callbackFlow }
        val p2pChannel = ensureChannel() ?: run { close(); return@callbackFlow }
        val connection = CompletableDeferred<DiscoveredPeer>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    runCatching {
                        wifiManager.requestGroupInfo(p2pChannel) { group ->
                            val owner = group?.owner
                            if (owner != null && !connection.isCompleted) {
                                weAreOwner = group.isGroupOwner
                                resolveOwnerAddress(wifiManager, p2pChannel) { host ->
                                    lastGroupOwner = host
                                    if (!connection.isCompleted) {
                                        connection.complete(
                                            DiscoveredPeer(
                                                id = owner.deviceAddress ?: "owner",
                                                name = owner.deviceName ?: "Wi-Fi Direct peer",
                                                transport = TransportType.LEGACY_WIFI_DIRECT,
                                                address = host,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        launch { connection.await().let { trySend(it) } }
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    override suspend fun stopDiscovery() {
        val p2pChannel = ensureChannel() ?: return
        runCatching { manager?.stopPeerDiscovery(p2pChannel, null) }
    }

    override suspend fun stopAdvertising() {
        val p2pChannel = ensureChannel() ?: return
        runCatching { manager?.removeGroup(p2pChannel, null) }
    }

    override suspend fun connect(peer: DiscoveredPeer): TransportSession {
        val wifiManager = manager ?: error("Wi-Fi Direct unavailable")
        val p2pChannel = ensureChannel() ?: error("Wi-Fi Direct unavailable")

        val deferred = CompletableDeferred<GroupHandle>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    runCatching {
                        wifiManager.requestGroupInfo(p2pChannel) { group ->
                            if (group?.owner != null && !deferred.isCompleted) {
                                val isOwner = group.isGroupOwner
                                resolveOwnerAddress(wifiManager, p2pChannel) { host ->
                                    if (!deferred.isCompleted) {
                                        deferred.complete(
                                            GroupHandle(
                                                ownerIp = host ?: "",
                                                isOwner = isOwner,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val config = WifiP2pConfig().apply {
            deviceAddress = peer.id
            if (Build.VERSION.SDK_INT >= 29) groupOwnerIntent = 0
        }
        runCatching { wifiManager.connect(p2pChannel, config, null) }
        // Also kick off a group so a peer that is not yet connected can still join.
        runCatching { wifiManager.createGroup(p2pChannel, null) }

        val handle = withTimeoutOrNull(GROUP_TIMEOUT_MS) { deferred.await() }
        runCatching { context.unregisterReceiver(receiver) }
        val group = handle ?: GroupHandle(ownerIp = peer.address ?: "", isOwner = false)
        lastGroupOwner = group.ownerIp
        weAreOwner = group.isOwner
        return LegacySession(peer, group)
    }

    private data class GroupHandle(val ownerIp: String, val isOwner: Boolean)

    private fun WifiP2pDevice.toPeer() = DiscoveredPeer(
        id = deviceAddress ?: deviceName,
        name = deviceName ?: "Unknown device",
        transport = TransportType.LEGACY_WIFI_DIRECT,
    )

    private fun serverSocket(port: Int, timeoutMs: Int = SOCKET_TIMEOUT_MS): ServerSocket =
        ServerSocket(port).apply { soTimeout = timeoutMs }

    private inner class LegacySession(
        override val peer: DiscoveredPeer,
        private val group: GroupHandle,
    ) : TransportSession {

        private var controlSocket: Socket? = null
        private var dataSocket: Socket? = null

        override suspend fun sendFile(file: TransferableFile): Flow<TransferProgress> = flow {
            val id = engine.begin(file, TransferDirection.OUTGOING, TransportType.LEGACY_WIFI_DIRECT)
            try {
                withContext(Dispatchers.IO) {
                    val source = File(requireNotNull(file.path) { "A file path is required" })
                    val control = openControlSocket()
                    val controlOut = DataOutputStream(control.getOutputStream())
                    val controlIn = DataInputStream(control.getInputStream())

                    controlOut.writeUtfLine(
                        ChunkProtocol.metadata(
                            files = listOf(
                                ChunkProtocol.FileMeta(
                                    id = file.id,
                                    name = file.name,
                                    size = file.sizeBytes,
                                    sha256 = file.sha256,
                                    mime = file.mimeType,
                                )
                            ),
                            totalBytes = file.sizeBytes,
                            dataPort = LegacyPorts.DATA_PORT,
                        )
                    )

                    val reply = ChunkProtocol.parseControl(controlIn.readUtfLine())
                    val startOffset = if (reply?.type == ChunkProtocol.ControlMessage.TYPE_RESUME) {
                        reply.value.coerceIn(0, file.sizeBytes)
                    } else 0L

                    openDataSocket()
                    val socketChannel = dataSocket!!.channel
                    streamFile(source, startOffset, id, controlOut, controlIn, socketChannel)
                }
            } catch (error: Exception) {
                engine.fail(id, error.message ?: "Wi-Fi Direct transfer failed")
            }
            val final = engine.progressFor(id)
            if (final != null) emit(final)
        }

        /** §5 — chunk headers from a direct buffer, payload copied with transferTo. */
        private fun streamFile(
            source: File,
            startOffset: Long,
            transferId: String,
            controlOut: DataOutputStream,
            controlIn: DataInputStream,
            socketChannel: java.nio.channels.SocketChannel,
        ) {
            val retransmitQueue = ConcurrentLinkedQueue<Int>()
            val channel = FileInputStream(source).channel
            channel.use { fileChannel ->
                var position = startOffset
                val total = source.length()
                var sequence = (startOffset / ChunkProtocol.CHUNK_SIZE).toInt()
                engine.update(transferId, position)

                while (position < total) {
                    // honour any outstanding retransmission requests first
                    while (retransmitQueue.isNotEmpty()) {
                        val requested = retransmitQueue.poll() ?: break
                        sendChunk(requested, fileChannel, socketChannel, total)
                    }
                    val length = min(ChunkProtocol.CHUNK_SIZE.toLong(), total - position).toInt()
                    sendChunk(sequence, fileChannel, socketChannel, total, position, length)
                    position += length
                    sequence++
                    engine.update(transferId, position)
                    readRetransmitRequests(controlIn, retransmitQueue)
                }
                while (retransmitQueue.isNotEmpty()) {
                    val requested = retransmitQueue.poll() ?: break
                    sendChunk(requested, fileChannel, socketChannel, total)
                }
            }
            runCatching { controlOut.writeUtfLine(ChunkProtocol.control(ChunkProtocol.ControlMessage.TYPE_DONE)) }
        }

        private fun sendChunk(
            sequence: Int,
            fileChannel: FileChannel,
            socketChannel: java.nio.channels.SocketChannel,
            total: Long,
            position: Long? = null,
            length: Int? = null,
        ) {
            val offset = position ?: (sequence.toLong() * ChunkProtocol.CHUNK_SIZE)
            val size = length
                ?: min(ChunkProtocol.CHUNK_SIZE.toLong(), (total - offset).coerceAtLeast(0)).toInt()
            if (size <= 0) return
            val payload = ByteBuffer.allocateDirect(size)
            var read = 0
            while (read < size) {
                val count = fileChannel.read(payload, offset + read)
                if (count <= 0) break
                read += count
            }
            payload.flip()
            val bytes = ByteArray(read)
            payload.get(bytes)
            val crc = ChunkProtocol.crc32(bytes, 0, read)

            socketChannel.write(ChunkProtocol.headerBuffer(sequence, read, crc))
            val body = ByteBuffer.wrap(bytes)
            while (body.hasRemaining()) socketChannel.write(body)
            socketChannel.write(ChunkProtocol.trailerBuffer(crc))
        }

        @Suppress("UNUSED_PARAMETER")
        override fun incomingFiles(): Flow<IncomingFileEvent> = callbackFlow {
            val job = scope.launch { receiveLoop { trySend(it) } }
            awaitClose { job.cancel() }
        }

        private suspend fun receiveLoop(emit: (IncomingFileEvent) -> Unit) = withContext(Dispatchers.IO) {
            val control = openControlSocket()
            val controlOut = DataOutputStream(control.getOutputStream())
            val controlIn = DataInputStream(control.getInputStream())
            val metadata = ChunkProtocol.parseMetadata(controlIn.readUtfLine())
                ?: return@withContext
            val directory = defaultDownloadDirectory(context)
            for (meta in metadata.files) {
                val target = File(directory, meta.name)
                val part = File(directory, meta.name + ".part")
                val offset = if (part.exists() && part.length() < meta.size) part.length() else 0L
                controlOut.writeUtfLine(ChunkProtocol.control(ChunkProtocol.ControlMessage.TYPE_RESUME, offset))

                val transferId = engine.begin(
                    TransferableFile(
                        id = meta.id.ifBlank { meta.name },
                        name = meta.name,
                        sizeBytes = meta.size,
                        mimeType = meta.mime,
                        path = target.absolutePath,
                        sha256 = meta.sha256,
                    ),
                    TransferDirection.INCOMING,
                    TransportType.LEGACY_WIFI_DIRECT,
                )
                emit(
                    IncomingFileEvent.Offered(
                        file = TransferableFile(
                            id = transferId,
                            name = meta.name,
                            sizeBytes = meta.size,
                            mimeType = meta.mime,
                        ),
                        senderName = peer.name,
                    )
                )

                if (!engine.hasSpaceFor(meta.size)) {
                    controlOut.writeUtfLine(
                        ChunkProtocol.control(ChunkProtocol.ControlMessage.TYPE_REJECT, text = "Not enough storage")
                    )
                    engine.fail(transferId, "Not enough storage on this device")
                    emit(IncomingFileEvent.Failed(transferId, "Not enough storage"))
                    continue
                }

                val data = openDataSocket()
                val received = receiveChunks(
                    data = data,
                    controlOut = controlOut,
                    part = part,
                    expectedSize = meta.size,
                    startOffset = offset,
                ) { bytes -> engine.update(transferId, bytes) }

                if (received) {
                    if (part.renameTo(target) || part.copyTo(target, overwrite = true).exists()) {
                        part.delete()
                    }
                    engine.complete(
                        fileId = transferId,
                        localPath = target.absolutePath,
                        peerName = peer.name,
                        publishToMediaStore = true,
                    )
                    emit(IncomingFileEvent.Done(transferId, target.absolutePath))
                } else {
                    engine.fail(transferId, "Transfer interrupted")
                    emit(IncomingFileEvent.Failed(transferId, "Transfer interrupted"))
                }
                closeSockets()
            }
        }

        private fun receiveChunks(
            data: Socket,
            controlOut: DataOutputStream,
            part: File,
            expectedSize: Long,
            startOffset: Long,
            onProgress: (Long) -> Unit,
        ): Boolean {
            val input = data.getInputStream()
            val output = FileOutputStream(part, startOffset > 0)
            var written = startOffset
            output.use { stream ->
                val header = ByteArray(ChunkProtocol.HEADER_BYTES)
                val trailer = ByteArray(ChunkProtocol.TRAILER_BYTES)
                while (written < expectedSize) {
                    if (!readFully(input, header)) return false
                    val buffer = ByteBuffer.wrap(header)
                    val magic = buffer.int
                    val sequence = buffer.int
                    val length = buffer.int
                    val expectedCrc = buffer.int
                    if (magic != ChunkProtocol.MAGIC || length <= 0 || length > ChunkProtocol.CHUNK_SIZE) {
                        return false
                    }
                    val payload = ByteArray(length)
                    if (!readFully(input, payload)) return false
                    if (!readFully(input, trailer)) return false
                    val actualCrc = ChunkProtocol.crc32(payload, 0, length)
                    if (actualCrc != expectedCrc) {
                        // ask for this chunk again and keep going
                        runCatching {
                            controlOut.writeUtfLine(
                                ChunkProtocol.control(
                                    ChunkProtocol.ControlMessage.TYPE_RETRANSMIT,
                                    sequence.toLong(),
                                )
                            )
                        }
                        continue
                    }
                    val chunkOffset = sequence.toLong() * ChunkProtocol.CHUNK_SIZE
                    if (chunkOffset != written) {
                        // out of order chunk: only accept the one we are missing
                        if (chunkOffset < written) continue
                        runCatching {
                            controlOut.writeUtfLine(
                                ChunkProtocol.control(
                                    ChunkProtocol.ControlMessage.TYPE_RETRANSMIT,
                                    (written / ChunkProtocol.CHUNK_SIZE),
                                )
                            )
                        }
                        continue
                    }
                    stream.write(payload)
                    written += length
                    onProgress(written)
                }
            }
            return written >= expectedSize
        }

        private fun openControlSocket(): Socket {
            controlSocket?.takeIf { it.isConnected && !it.isClosed }?.let { return it }
            val socket = if (group.isOwner) {
                serverSocket(LegacyPorts.CONTROL_PORT).accept()
            } else {
                connectWithRetry(group.ownerIp, LegacyPorts.CONTROL_PORT)
            }
            socket.soTimeout = SOCKET_TIMEOUT_MS
            controlSocket = socket
            return socket
        }

        private fun openDataSocket(): Socket {
            dataSocket?.takeIf { it.isConnected && !it.isClosed }?.let { return it }
            val socket = if (group.isOwner) {
                serverSocket(LegacyPorts.DATA_PORT).accept()
            } else {
                connectWithRetry(group.ownerIp, LegacyPorts.DATA_PORT)
            }
            socket.soTimeout = SOCKET_TIMEOUT_MS
            dataSocket = socket
            return socket
        }

        private fun connectWithRetry(host: String, port: Int): Socket {
            var lastError: IOException? = null
            repeat(CONNECT_ATTEMPTS) { attempt ->
                runCatching { Socket(host, port) }
                    .onSuccess { return it }
                    .onFailure { lastError = it as? IOException ?: IOException(it) }
                runCatching { Thread.sleep(400L * (attempt + 1)) }
            }
            throw lastError ?: IOException("Unable to reach $host:$port")
        }

        private fun readFully(input: java.io.InputStream, destination: ByteArray): Boolean {
            var offset = 0
            while (offset < destination.size) {
                val read = input.read(destination, offset, destination.size - offset)
                if (read < 0) return false
                offset += read
            }
            return true
        }

        private fun closeSockets() {
            runCatching { controlSocket?.close() }
            runCatching { dataSocket?.close() }
            controlSocket = null
            dataSocket = null
        }

        override suspend fun close() {
            closeSockets()
            stopAdvertising()
        }
    }

    private fun DataOutputStream.writeUtfLine(value: String) {
        write((value + "\n").toByteArray(Charsets.UTF_8))
        flush()
    }

    private fun DataInputStream.readUtfLine(): String =
        readLine() ?: ""

    private fun readRetransmitRequests(input: DataInputStream?, queue: ConcurrentLinkedQueue<Int>) {
        // Retransmission requests arrive asynchronously on the control channel;
        // they are drained before each subsequent chunk is written.
        if (input == null) return
        runCatching {
            while (input.available() > 0) {
                val message = ChunkProtocol.parseControl(input.readUtfLine()) ?: continue
                if (message.type == ChunkProtocol.ControlMessage.TYPE_RETRANSMIT) {
                    queue.offer(message.value.toInt())
                }
            }
        }
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val GROUP_TIMEOUT_MS = 20_000L
        private const val CONNECT_ATTEMPTS = 6

        /** §7 — concurrent chunk handlers scale with the device tier. */
        fun recommendedThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
