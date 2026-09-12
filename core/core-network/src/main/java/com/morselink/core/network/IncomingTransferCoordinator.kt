package com.morselink.core.network

import android.util.Log
import com.morselink.core.transfer.model.IncomingFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the receive side of a session for as long as the session lives.
 *
 * A session only receives while something is collecting [TransportSession.incomingFiles]:
 * the flow is what starts the receive loop, and nothing else does. Wiring that
 * collection to a screen meant the receiving phone completed the handshake, said
 * "connected", and then never read a single byte — the sender sat waiting for a
 * reply that was never going to come until its socket timed out.
 *
 * This holds the collection at process scope instead, so receiving survives the
 * user leaving the transfer screen to go and pick more files.
 */
@Singleton
class IncomingTransferCoordinator @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: kotlinx.coroutines.Job? = null

    private val _events = MutableSharedFlow<IncomingFileEvent>(
        replay = 32,
        extraBufferCapacity = 32,
    )

    /** Everything the receive loop has reported, for anything that wants to show it. */
    val events: SharedFlow<IncomingFileEvent> = _events.asSharedFlow()

    /**
     * Starts receiving on [session]. Repeat calls are ignored while a loop is
     * already running, because a second collection would just race the first for
     * the same control socket.
     */
    suspend fun start(session: TransportSession) {
        val running = job
        if (running?.isActive == true) return
        // A loop that has been cancelled but has not finished yet would race
        // the new one for the same socket, and in its last moments could read
        // the line the other phone is sending. Wait for it to go.
        if (running != null) {
            job = null
            runCatching { withTimeoutOrNull(5_000) { running.cancelAndJoin() } }
        }
        Log.d("Morselink", "rx: starting receive loop for ${session.peer.name}")
        job = scope.launch {
            runCatching {
                session.incomingFiles().collect { event ->
                    Log.d("Morselink", "rx: ${describe(event)}")
                    _events.emit(event)
                }
            }.onFailure { error ->
                Log.w("Morselink", "rx: receive loop ended: ${error.message}")
            }
            Log.d("Morselink", "rx: receive loop finished")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Stops the loop and waits until it has actually finished.
     *
     * cancel() alone is not enough. The loop polls the control socket, so after
     * cancellation it still has up to one poll interval left to run - and in
     * that window it reads the reply to the very handshake the sender is about
     * to write. The sender then waits for a resume that its own receiver has
     * just swallowed, and the file fails on a read timeout. Handing the channel
     * over has to wait for the old loop to be truly gone.
     */
    suspend fun stopAndJoin(timeoutMs: Long = 5_000) {
        val running = job ?: return
        job = null
        runCatching {
            withTimeoutOrNull(timeoutMs) { running.cancelAndJoin() }
        }
    }

    private fun describe(event: IncomingFileEvent): String = when (event) {
        is IncomingFileEvent.Offered -> "offered ${event.file.name} (${event.file.sizeBytes} B) from ${event.senderName}"
        is IncomingFileEvent.Chunk -> "chunk ${event.fileId} -> ${event.bytes} B"
        is IncomingFileEvent.Done -> "saved ${event.fileId} to ${event.localPath}"
        is IncomingFileEvent.Failed -> "failed ${event.fileId}: ${event.reason}"
        else -> event.toString()
    }
}
