package com.morselink.core.transfer.engine

import com.morselink.core.data.TransferHistoryRepository
import com.morselink.core.data.db.TransferEntity
import com.morselink.core.media.FileOps
import com.morselink.core.transfer.model.IncomingFileEvent
import com.morselink.core.transfer.model.TransferDirection
import com.morselink.core.transfer.model.TransferProgress
import com.morselink.core.transfer.model.TransferSessionState
import com.morselink.core.transfer.model.TransferStatus
import com.morselink.core.transfer.model.TransferableFile
import com.morselink.core.transfer.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §5 — transport-agnostic transfer bookkeeping.
 *
 * Owns: progress state (two independent maps, one per direction), the
 * ~100ms progress cadence, sliding-window speed, ETA, cancellation and
 * post-transfer finalisation (temp rename, MediaStore insert, history row).
 * It does not own chunking for Nearby Connections or WebShare.
 */
@Singleton
class TransferEngine @Inject constructor(
    private val history: TransferHistoryRepository,
    private val fileOps: FileOps,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sequence = AtomicLong(1)

    private val trackers = ConcurrentHashMap<String, Tracker>()
    private val _state = MutableStateFlow(TransferSessionState())
    val state: StateFlow<TransferSessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<IncomingFileEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<IncomingFileEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            while (true) {
                delay(PUBLISH_INTERVAL_MS)
                publish()
            }
        }
    }

    fun nextId(): String = "t${sequence.getAndIncrement()}"

    fun begin(
        file: TransferableFile,
        direction: TransferDirection,
        transport: TransportType,
    ): String {
        val id = file.id
        trackers[id] = Tracker(
            progress = TransferProgress(
                fileId = id,
                name = file.name,
                sizeBytes = file.sizeBytes,
                mimeType = file.mimeType,
                direction = direction,
                transport = transport,
                status = TransferStatus.IN_PROGRESS,
            ),
            file = file,
        )
        publish()
        return id
    }

    fun update(fileId: String, bytesTransferred: Long) {
        val tracker = trackers[fileId] ?: return
        tracker.addSample(bytesTransferred)
    }

    /** §10 — reject an incoming set before a single byte is written if there is no room. */
    fun hasSpaceFor(totalBytes: Long): Boolean {
        val usable = fileOps.usableSpace()
        return usable <= 0L || usable - totalBytes > RESERVE_BYTES
    }

    suspend fun complete(
        fileId: String,
        localPath: String? = null,
        uriString: String? = null,
        peerName: String = "",
        publishToMediaStore: Boolean = false,
    ) {
        val tracker = trackers[fileId] ?: return
        tracker.progress = tracker.progress.copy(
            bytesTransferred = tracker.progress.sizeBytes,
            status = TransferStatus.COMPLETED,
            errorMessage = null,
        )
        publish()

        var finalUri = uriString
        if (publishToMediaStore && localPath != null) {
            val file = File(localPath)
            val mime = tracker.file.mimeType
            val uri = runCatching { fileOps.publishToMediaStore(file, mime) }.getOrNull()
            if (uri != null) finalUri = uri.toString()
        }

        history.record(
            TransferEntity(
                name = tracker.progress.name,
                sizeBytes = tracker.progress.sizeBytes,
                mimeType = tracker.progress.mimeType ?: "application/octet-stream",
                direction = if (tracker.progress.direction == TransferDirection.INCOMING)
                    TransferEntity.DIRECTION_RECEIVED else TransferEntity.DIRECTION_SENT,
                peerName = peerName.ifBlank { "Unknown device" },
                transport = tracker.progress.transport.name,
                status = TransferEntity.STATUS_SUCCESS,
                timestamp = System.currentTimeMillis(),
                localPath = localPath,
                uriString = finalUri,
            )
        )
        _events.emit(IncomingFileEvent.Done(fileId, localPath))
    }

    suspend fun fail(fileId: String, reason: String, peerName: String = "") {
        val tracker = trackers[fileId] ?: return
        tracker.progress = tracker.progress.copy(status = TransferStatus.FAILED, errorMessage = reason)
        publish()
        history.record(
            TransferEntity(
                name = tracker.progress.name,
                sizeBytes = tracker.progress.sizeBytes,
                mimeType = tracker.progress.mimeType ?: "application/octet-stream",
                direction = if (tracker.progress.direction == TransferDirection.INCOMING)
                    TransferEntity.DIRECTION_RECEIVED else TransferEntity.DIRECTION_SENT,
                peerName = peerName.ifBlank { "Unknown device" },
                transport = tracker.progress.transport.name,
                status = TransferEntity.STATUS_FAILED,
                timestamp = System.currentTimeMillis(),
                localPath = tracker.file.path,
                errorMessage = reason,
            )
        )
        _events.emit(IncomingFileEvent.Failed(fileId, reason))
    }

    suspend fun cancel(fileId: String) {
        val tracker = trackers[fileId] ?: return
        tracker.progress = tracker.progress.copy(status = TransferStatus.CANCELLED)
        publish()
        history.record(
            TransferEntity(
                name = tracker.progress.name,
                sizeBytes = tracker.progress.sizeBytes,
                mimeType = tracker.progress.mimeType ?: "application/octet-stream",
                direction = if (tracker.progress.direction == TransferDirection.INCOMING)
                    TransferEntity.DIRECTION_RECEIVED else TransferEntity.DIRECTION_SENT,
                peerName = "Unknown device",
                transport = tracker.progress.transport.name,
                status = TransferEntity.STATUS_CANCELLED,
                timestamp = System.currentTimeMillis(),
                localPath = tracker.file.path,
            )
        )
    }

    fun cancelAll() {
        trackers.values.forEach { tracker ->
            if (!tracker.progress.isFinished) {
                tracker.progress = tracker.progress.copy(status = TransferStatus.CANCELLED)
            }
        }
        publish()
    }

    fun progressFor(fileId: String): TransferProgress? = trackers[fileId]?.progress

    fun clearFinished() {
        trackers.entries.removeIf { it.value.progress.isFinished }
        publish()
    }

    private fun publish() {
        val outgoing = LinkedHashMap<String, TransferProgress>()
        val incoming = LinkedHashMap<String, TransferProgress>()
        for (tracker in trackers.values) {
            val progress = tracker.snapshot()
            when (progress.direction) {
                TransferDirection.OUTGOING -> outgoing[progress.fileId] = progress
                TransferDirection.INCOMING -> incoming[progress.fileId] = progress
            }
        }
        _state.value = TransferSessionState(outgoing = outgoing, incoming = incoming)
    }

    private class Tracker(
        var progress: TransferProgress,
        val file: TransferableFile,
    ) {
        private val samples = ArrayDeque<Sample>(64)

        @Synchronized
        fun addSample(bytes: Long) {
            val now = System.currentTimeMillis()
            samples.addLast(Sample(now, bytes))
            while (samples.size > SAMPLE_WINDOW) samples.removeFirst()
            val oldest = samples.peekFirst()
            val elapsed = now - oldest.timeMs
            val speed = if (elapsed > 250L) ((bytes - oldest.bytes) * 1000L) / elapsed else 0L
            val remaining = (progress.sizeBytes - bytes).coerceAtLeast(0L)
            progress = progress.copy(
                bytesTransferred = bytes,
                bytesPerSecond = speed.coerceAtLeast(0L),
                etaSeconds = if (speed > 0L) remaining / speed else -1L,
            )
        }

        @Synchronized
        fun snapshot(): TransferProgress = progress

        private data class Sample(val timeMs: Long, val bytes: Long)
    }

    companion object {
        private const val PUBLISH_INTERVAL_MS = 100L
        private const val SAMPLE_WINDOW = 20
        private const val RESERVE_BYTES = 32L * 1024 * 1024
    }
}
