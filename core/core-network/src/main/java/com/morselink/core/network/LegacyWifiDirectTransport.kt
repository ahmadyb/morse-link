package com.morselink.core.network

import android.util.Log

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
import com.morselink.core.transfer.MorselinkLog
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        // A session used to be handed back even when nothing was on the other
        // end, so typing a made-up port still reported "connected" and then sat
        // there forever. Verify the endpoint before claiming success.
        if (handle == null) {
            val host = group.ownerIp
            val port = peer.port
            if (host.isBlank() || port <= 0) {
                error("No address to connect to")
            }
            runCatching {
                Socket().use { it.connect(java.net.InetSocketAddress(host, port), 4_000) }
            }.onFailure { cause ->
                throw IOException(
                    "Could not reach $host:$port. Make sure the sender is still " +
                        "showing its code on this network.",
                    cause,
                )
            }
        }

        lastGroupOwner = group.ownerIp
        weAreOwner = group.isOwner
        return LegacySession(peer, group)
    }

    private data class GroupHandle(val ownerIp: String, val isOwner: Boolean)

    /** A control socket plus the peer it came from. */
    data class DirectConnection(val socket: Socket, val peer: DiscoveredPeer)

    /**
     * Direct TCP for the QR and manual-connect flows.
     *
     * Wi-Fi Direct is not involved: this works on any network the two phones
     * share, including a hotspot created by either of them. The sender binds
     * and waits, the receiver dials in and says who it is, and the normal
     * chunk protocol then runs over the socket that was just opened.
     */
    suspend fun hostDirect(
        localName: String,
        port: Int = LegacyPorts.CONTROL_PORT,
    ): DirectConnection? =
        withContext(Dispatchers.IO) {
            runCatching {
                val server = ServerSocket(port).apply { soTimeout = DIRECT_ACCEPT_TIMEOUT_MS }
                MorselinkLog.d("transport: listening on $port")
                val socket = try {
                    server.accept()
                } finally {
                    runCatching { server.close() }
                }
                socket.soTimeout = SOCKET_TIMEOUT_MS
                MorselinkLog.d("transport: inbound connection accepted")
                val stream = socket.getInputStream()
                val name = runCatching { DataInputStream(stream).readUtfLine() }
                    .getOrNull()?.trim().orEmpty().ifBlank { "Receiver" }
                runCatching {
                    DataOutputStream(socket.getOutputStream()).writeUtfLine(localName)
                }
                val address = socket.inetAddress?.hostAddress.orEmpty()
                DirectConnection(
                    socket = socket,
                    peer = DiscoveredPeer(
                        id = address,
                        name = name,
                        transport = TransportType.LEGACY_WIFI_DIRECT,
                        address = address,
                        port = port,
                    ),
                )
            }.getOrNull()
        }

    suspend fun joinDirect(host: String, port: Int, localName: String): DirectConnection? =
        withContext(Dispatchers.IO) {
            runCatching {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(host, port), DIRECT_CONNECT_TIMEOUT_MS)
                socket.soTimeout = SOCKET_TIMEOUT_MS
                MorselinkLog.d("transport: connected to $host:$port")
                DataOutputStream(socket.getOutputStream()).writeUtfLine(localName)
                val remoteName = runCatching {
                    DataInputStream(socket.getInputStream()).readUtfLine()
                }.getOrNull()?.trim().orEmpty().ifBlank { host }
                MorselinkLog.d("transport: sender identified itself as $remoteName")
                DirectConnection(
                    socket = socket,
                    peer = DiscoveredPeer(
                        id = host,
                        name = remoteName,
                        transport = TransportType.LEGACY_WIFI_DIRECT,
                        address = host,
                        port = port,
                    ),
                )
            }.getOrNull()
        }

    fun sessionForDirect(connection: DirectConnection, isHost: Boolean): TransportSession =
        LegacySession(
            connection.peer,
            GroupHandle(ownerIp = connection.peer.address.orEmpty(), isOwner = isHost),
            connection.socket,
        )

    private fun WifiP2pDevice.toPeer() = DiscoveredPeer(
        id = deviceAddress ?: deviceName,
        name = deviceName ?: "Unknown device",
        transport = TransportType.LEGACY_WIFI_DIRECT,
    )

    /**
     * Binds with SO_REUSEADDR so a port left in TIME_WAIT by a previous
     * session does not fail the next bind.
     */
    private fun serverSocket(port: Int, timeoutMs: Int = SOCKET_TIMEOUT_MS): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(port))
            soTimeout = timeoutMs
        }

    private inner class LegacySession(
        override val peer: DiscoveredPeer,
        private val group: GroupHandle,
        /** Set when the control socket was made by the direct TCP handshake. */
        presetControl: Socket? = null,
    ) : TransportSession {

        private var controlSocket: Socket? = null
        private var dataSocket: Socket? = null
        private var preset: Socket? = presetControl
        private var hadControl: Boolean = false
        private val sendMutex = Mutex()

        override suspend fun sendFile(file: TransferableFile): Flow<TransferProgress> = flow {
            val id = engine.begin(file, TransferDirection.OUTGOING, TransportType.LEGACY_WIFI_DIRECT)
            try {
                withContext(Dispatchers.IO) {
                    // One control channel, one file at a time: two concurrent
                    // sends would interleave their handshakes on the same
                    // socket and corrupt each other.
                    sendMutex.withLock {
                        val source = resolveSource(file)
                        MorselinkLog.d("tx: ${file.name} size=${file.sizeBytes} from ${source.absolutePath}")
                        val control = openControlSocket()
                        val controlOut = DataOutputStream(control.getOutputStream())
                        val controlIn = DataInputStream(control.getInputStream())

                        // Bind the data port before announcing the file. The
                        // receiver dials in as soon as it has read the
                        // metadata; if nothing is listening yet its first
                        // connect is refused and it has to back off and retry.
                        val dataServer = bindDataServer()
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

                        MorselinkLog.d("tx: ${file.name} metadata sent, waiting for resume")
                        val reply = ChunkProtocol.parseControl(controlIn.readUtfLine())
                        val startOffset = if (reply?.type == ChunkProtocol.ControlMessage.TYPE_RESUME) {
                            reply.value.coerceIn(0, file.sizeBytes)
                        } else 0L
                        MorselinkLog.d("tx: ${file.name} resume=$startOffset (reply=${reply?.type})")

                        val data = try {
                            openDataSocket(dataServer)
                        } finally {
                            runCatching { dataServer?.close() }
                        }
                        val out = java.io.BufferedOutputStream(
                            data.getOutputStream(),
                            ChunkProtocol.CHUNK_SIZE + ChunkProtocol.HEADER_BYTES + ChunkProtocol.TRAILER_BYTES,
                        )
                        MorselinkLog.d("tx: data socket open, streaming ${file.name}")
                        streamFile(source, startOffset, id, controlOut, controlIn, out)
                        // Only the failure branch called into the engine, so a
                        // send that actually worked left its row unfinished and
                        // wrote no history entry at all. That is why the
                        // receiving handset had a Sent/Received list and the
                        // sending one showed nothing for the same transfers.
                        // publishToMediaStore is false: the file already lives
                        // in this device's gallery, it only needs recording.
                        engine.complete(
                            fileId = id,
                            localPath = source.absolutePath,
                            peerName = peer.name,
                            publishToMediaStore = false,
                        )
                        MorselinkLog.d("tx: ${file.name} sent to ${peer.name}")
                    }
                }
            } catch (error: Exception) {
                MorselinkLog.d("tx: ${file.name} FAILED ${error.javaClass.simpleName}: ${error.message}")
                val detail = error.message?.takeIf { it.isNotBlank() }
                    ?: "Transfer failed (${error.javaClass.simpleName})"
                engine.fail(id, detail)
            }
            val final = engine.progressFor(id)
            if (final != null) emit(final)
        }

        /** §5 — chunk headers, payload and trailer written to one buffered stream. */
        private fun streamFile(
            source: File,
            startOffset: Long,
            transferId: String,
            controlOut: DataOutputStream,
            controlIn: DataInputStream,
            out: java.io.OutputStream,
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
                        sendChunk(requested, fileChannel, out, total)
                    }
                    val length = min(ChunkProtocol.CHUNK_SIZE.toLong(), total - position).toInt()
                    sendChunk(sequence, fileChannel, out, total, position, length)
                    position += length
                    sequence++
                    engine.update(transferId, position)
                    readRetransmitRequests(controlIn, retransmitQueue)
                }
                while (retransmitQueue.isNotEmpty()) {
                    val requested = retransmitQueue.poll() ?: break
                    sendChunk(requested, fileChannel, out, total)
                }
            }
            out.flush()
            runCatching { controlOut.writeUtfLine(ChunkProtocol.control(ChunkProtocol.ControlMessage.TYPE_DONE)) }
        }

        private fun sendChunk(
            sequence: Int,
            fileChannel: FileChannel,
            out: java.io.OutputStream,
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

            out.write(ChunkProtocol.headerBytes(sequence, read, crc))
            out.write(bytes, 0, read)
            out.write(ChunkProtocol.trailerBytes(crc))
            // Push every chunk onto the wire now. Buffering across chunks
            // left the tail of each file in memory, so the sender reported
            // 100% while the receiver was still waiting for bytes that were
            // never sent.
            out.flush()
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
            val directory = defaultDownloadDirectory(context)
            MorselinkLog.d("rx: receiver loop started")

            // The sender runs one metadata handshake per file, so this has to
            // keep reading rather than stopping after the first batch. The
            // sockets also have to stay open between files: closing them after
            // every file left the sender writing into a dead connection, which
            // is what produced "Socket closed" from the second file onwards.
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val line = runCatching { controlIn.readUtfLine() }.getOrNull()
                    if (line.isNullOrBlank()) {
                        MorselinkLog.d("rx: control channel closed")
                        break
                    }
                    val metadata = ChunkProtocol.parseMetadata(line)
                    if (metadata == null) {
                        // Not metadata, so keep reading. The sender writes a
                        // DONE marker on this channel after every file, and
                        // treating that as end-of-conversation ended the
                        // session after the first file: every later file was
                        // then left waiting for a receiver that had gone.
                        MorselinkLog.d("rx: skipping control line: ${line.take(80)}")
                        continue
                    }
                    for (meta in metadata.files) {
                        try {
                            receiveOne(meta, directory, controlOut, emit)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            // The stream is mid-message and its position is
                            // unknown, so there is no safe way to carry on with
                            // the next file on this connection.
                            Log.w(
                                "Morselink",
                                "rx: ${meta.name} failed: ${error.javaClass.simpleName}: ${error.message}",
                            )
                            emit(IncomingFileEvent.Failed(meta.id.ifBlank { meta.name }, error.describe()))
                            throw error
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                MorselinkLog.w("rx: receive loop stopped: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                MorselinkLog.d("rx: receiver loop ended")
                closeSockets()
            }
        }

        private fun Throwable.describe(): String =
            message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

        private suspend fun receiveOne(
            meta: ChunkProtocol.FileMeta,
            directory: File,
            controlOut: DataOutputStream,
            emit: (IncomingFileEvent) -> Unit,
        ) {
            val target = File(directory, meta.name)
            val part = File(directory, meta.name + ".part")
            val offset = if (part.exists() && part.length() < meta.size) part.length() else 0L
            MorselinkLog.d("rx: ${meta.name} metadata ok (${meta.size} B), resuming at $offset")
            controlOut.writeUtfLine(ChunkProtocol.control(ChunkProtocol.ControlMessage.TYPE_RESUME, offset))
            MorselinkLog.d("rx: ${meta.name} resume sent")

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
                return
            }

            val data = openDataSocket()
            MorselinkLog.d("rx: ${meta.name} data socket open, reading chunks")
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
        }

        /**
         * Returns false when the stream ended early or broke, rather than
         * throwing. A socket timeout escaping from here used to take the whole
         * app down: the loop runs in the transport's own coroutine, so nothing
         * upstream was in a position to catch it.
         */
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
            return try {
                readChunks(input, output, controlOut, part, expectedSize, startOffset, onProgress)
            } catch (error: java.io.IOException) {
                Log.w(
                    "Morselink",
                    "rx: chunk read failed after $written/$expectedSize B: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
                false
            } finally {
                runCatching { output.close() }
            }
        }

        private fun readChunks(
            input: java.io.InputStream,
            output: FileOutputStream,
            controlOut: DataOutputStream,
            part: File,
            expectedSize: Long,
            startOffset: Long,
            onProgress: (Long) -> Unit,
        ): Boolean {
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
            val handedOver = preset
            val socket = if (handedOver != null) {
                preset = null
                handedOver
            } else if (hadControl) {
                // The session already had a control channel and it has gone
                // away. Waiting for a fresh inbound connection would hang
                // until the socket timed out, so report it instead.
                throw IOException("The connection to ${peer.name} was closed")
            } else if (group.isOwner) {
                val server = serverSocket(LegacyPorts.CONTROL_PORT)
                try { server.accept() } finally { runCatching { server.close() } }
            } else {
                connectWithRetry(group.ownerIp, LegacyPorts.CONTROL_PORT)
            }
            socket.soTimeout = SOCKET_TIMEOUT_MS
            controlSocket = socket
            hadControl = true
            return socket
        }

        /**
         * Binds the data port without accepting yet, so the port is already
         * listening by the time the receiver is told to dial in. Pass the
         * result to [openDataSocket] and close it afterwards.
         */
        private fun bindDataServer(): ServerSocket? =
            if (dataSocket == null && group.isOwner) serverSocket(LegacyPorts.DATA_PORT) else null

        private fun openDataSocket(prebound: ServerSocket? = null): Socket {
            dataSocket?.takeIf { it.isConnected && !it.isClosed }?.let { return it }
            val socket = when {
                prebound != null -> prebound.accept()
                group.isOwner -> {
                    val server = serverSocket(LegacyPorts.DATA_PORT)
                    try { server.accept() } finally { runCatching { server.close() } }
                }
                else -> connectWithRetry(group.ownerIp, LegacyPorts.DATA_PORT)
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

        /**
         * A sendable source of bytes for [file].
         *
         * The path is preferred, but scoped storage can leave it null or
         * unreadable even when the content is perfectly shareable. In that case
         * the content is copied out of its Uri into a cache file so the chunked
         * sender can keep using a FileChannel.
         */
        private fun resolveSource(file: TransferableFile): File {
            file.path?.takeIf { it.isNotBlank() }
                ?.let(::File)?.takeIf { it.canRead() }
                ?.let { return it }

            val uri = file.uri
                ?: throw IllegalArgumentException("A file path or a readable source is required")
            val temp = File(context.cacheDir, "outgoing-${file.id.hashCode()}-${file.name}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Could not open ${file.name}")
            temp.deleteOnExit()
            return temp
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

        /** How long the sender holds the port open waiting for a scan. */
        private const val DIRECT_ACCEPT_TIMEOUT_MS = 120_000
        private const val DIRECT_CONNECT_TIMEOUT_MS = 8_000
        private const val CONNECT_ATTEMPTS = 6

        /** §7 — concurrent chunk handlers scale with the device tier. */
        fun recommendedThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
