package com.morselink.core.network

import com.morselink.core.transfer.MorselinkLog
import com.morselink.core.transfer.engine.TransferEngine
import com.morselink.core.transfer.model.TransferDirection
import com.morselink.core.transfer.model.TransferableFile
import com.morselink.core.transfer.model.TransportType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the send side of a session for as long as the session lives.
 *
 * The mirror of [IncomingTransferCoordinator], and needed for the same
 * reason. Sending used to run in the transfer screen's ViewModel scope, so
 * leaving that screen cancelled the job and took the transfer with it — which
 * made "minimise this screen and go and pick more files" impossible: you could
 * get back to the file list only by abandoning whatever was in flight.
 *
 * Work launched here is at process scope, so a send keeps running while the
 * user is anywhere else in the app, and the transfer screen re-attaches to it
 * when they come back.
 */
@Singleton
class OutgoingTransferCoordinator @Inject constructor(
    private val engine: TransferEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // A control socket is a single bidirectional byte stream. Separate send jobs
    // must never write metadata at the same time or the receiver sees garbled JSON.
    private val sessionMutex = Mutex()

    /**
     * Sends [files] over [session].
     *
     * Each call is its own job rather than a single queue, so a batch picked
     * after an earlier one started does not have to wait for it to be
     * collected by hand. The transport serialises them: sends take the
     * session's mutex, because two concurrent handshakes on one control
     * socket would interleave and corrupt each other.
     */
    fun send(
        session: TransportSession,
        files: List<TransferableFile>,
        transport: TransportType,
        /**
         * Called once this batch has finished, so the caller can hand the
         * channel over. The control socket carries both directions, and only
         * one side may be talking at a time, so whoever was sending has to
         * start listening afterwards or the other phone can never reply.
         */
        onFinished: (() -> Unit)? = null,
    ) {
        if (files.isEmpty()) {
            onFinished?.invoke()
            return
        }
        MorselinkLog.d("tx: queueing ${files.size} file(s) for ${session.peer.name}")
        scope.launch {
            try {
                sessionMutex.withLock {
                    files.forEach { file ->
                    try {
                        val id = engine.begin(file, TransferDirection.OUTGOING, transport)
                        session.sendFile(file).collect { progress ->
                            engine.update(progress.fileId.ifBlank { id }, progress.bytesTransferred)
                        }
                    } catch (error: Throwable) {
                        // Cancellation is not a failure: rethrow it or cancelling
                        // would only ever mark files failed and then keep sending.
                        if (error is CancellationException) throw error
                        engine.fail(file.id, error.message ?: "Send failed")
                    }
                    }
                }
                MorselinkLog.d("tx: batch of ${files.size} finished")
                onFinished?.invoke()
            } catch (error: CancellationException) {
                MorselinkLog.d("tx: batch cancelled")
            }
        }
    }

    /** Cancels every send in flight. Used by Cancel, never by Minimise. */
    fun stop() {
        scope.coroutineContext.cancelChildren()
    }
}
