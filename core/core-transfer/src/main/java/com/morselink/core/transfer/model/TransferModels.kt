package com.morselink.core.transfer.model

import android.net.Uri

/** §4.1 — which physical medium a session is running over. */
enum class TransportType {
    NEARBY_CONNECTIONS,
    LEGACY_WIFI_DIRECT,
    WEBSHARE_HTTP,
}

fun TransportType.label(): String = when (this) {
    TransportType.NEARBY_CONNECTIONS -> "Nearby Connections"
    TransportType.LEGACY_WIFI_DIRECT -> "Wi-Fi Direct"
    TransportType.WEBSHARE_HTTP -> "WebShare"
}

enum class TransferDirection { OUTGOING, INCOMING }

enum class TransferStatus { QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

data class TransferableFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val uri: Uri? = null,
    val path: String? = null,
    val sha256: String? = null,
)

data class TransferProgress(
    val fileId: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val direction: TransferDirection,
    val transport: TransportType,
    val bytesTransferred: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long = -1L,
    val errorMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float
        get() = if (sizeBytes <= 0L) 0f else (bytesTransferred.toFloat() / sizeBytes).coerceIn(0f, 1f)

    val isFinished: Boolean
        get() = status == TransferStatus.COMPLETED ||
            status == TransferStatus.FAILED ||
            status == TransferStatus.CANCELLED
}

/** §4.3 — session-scoped state keyed by file id, never a single global object. */
data class TransferSessionState(
    val outgoing: Map<String, TransferProgress> = emptyMap(),
    val incoming: Map<String, TransferProgress> = emptyMap(),
) {
    val isEmpty: Boolean get() = outgoing.isEmpty() && incoming.isEmpty()

    fun all(): List<TransferProgress> = outgoing.values + incoming.values
}

sealed interface IncomingFileEvent {
    data class Offered(val file: TransferableFile, val senderName: String) : IncomingFileEvent
    data class Chunk(val fileId: String, val bytes: Long) : IncomingFileEvent
    data class Done(val fileId: String, val localPath: String?) : IncomingFileEvent
    data class Failed(val fileId: String, val reason: String) : IncomingFileEvent
}
