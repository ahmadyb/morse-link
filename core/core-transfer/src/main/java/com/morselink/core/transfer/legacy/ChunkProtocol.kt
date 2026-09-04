package com.morselink.core.transfer.legacy

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * §4.4 — the manual protocol for the legacy Wi-Fi Direct path, where no SDK
 * provides chunking, sequencing or delivery confirmation for us.
 *
 * Wire format for every data chunk:
 *   magic(4) | sequence(4) | length(4) | payload(length) | crc32(4)
 */
object ChunkProtocol {

    const val MAGIC = 0x4D4C4E4B // "MLNK"
    const val CHUNK_SIZE = 64 * 1024
    const val HEADER_BYTES = 16
    const val TRAILER_BYTES = 4

    /** Header plus payload are written from a direct buffer to keep copies low (§5). */
    fun headerBuffer(sequence: Int, payloadLength: Int, crc: Int): ByteBuffer =
        ByteBuffer.allocateDirect(HEADER_BYTES).apply {
            putInt(MAGIC)
            putInt(sequence)
            putInt(payloadLength)
            putInt(crc)
            flip()
        }

    fun trailerBuffer(crc: Int): ByteBuffer =
        ByteBuffer.allocateDirect(TRAILER_BYTES).apply {
            putInt(crc)
            flip()
        }

    fun crc32(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }

    fun metadata(files: List<FileMeta>, totalBytes: Long, dataPort: Int): String =
        JSONObject().apply {
            put("dataPort", dataPort)
            put("totalBytes", totalBytes)
            put("files", JSONArray().apply {
                files.forEach { file ->
                    put(JSONObject().apply {
                        put("id", file.id)
                        put("name", file.name)
                        put("size", file.size)
                        put("sha256", file.sha256 ?: "")
                        put("mime", file.mime ?: "")
                    })
                }
            })
        }.toString()

    fun parseMetadata(json: String): Metadata? = runCatching {
        val root = JSONObject(json)
        val files = mutableListOf<FileMeta>()
        val array = root.optJSONArray("files") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            files.add(
                FileMeta(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    size = item.optLong("size"),
                    sha256 = item.optString("sha256").takeIf { it.isNotEmpty() },
                    mime = item.optString("mime").takeIf { it.isNotEmpty() },
                )
            )
        }
        Metadata(
            files = files,
            totalBytes = root.optLong("totalBytes"),
            dataPort = root.optInt("dataPort", LegacyPorts.DATA_PORT),
        )
    }.getOrNull()

    /** Control messages exchanged on the control socket. */
    fun control(type: String, value: Long = 0L, text: String = ""): String =
        JSONObject().apply {
            put("type", type)
            put("value", value)
            put("text", text)
        }.toString()

    fun parseControl(json: String): ControlMessage? = runCatching {
        val root = JSONObject(json)
        ControlMessage(
            type = root.optString("type"),
            value = root.optLong("value"),
            text = root.optString("text"),
        )
    }.getOrNull()

    data class FileMeta(
        val id: String,
        val name: String,
        val size: Long,
        val sha256: String?,
        val mime: String?,
    )

    data class Metadata(
        val files: List<FileMeta>,
        val totalBytes: Long,
        val dataPort: Int,
    )

    data class ControlMessage(val type: String, val value: Long, val text: String) {
        companion object {
            const val TYPE_ACCEPT = "accept"
            const val TYPE_REJECT = "reject"
            const val TYPE_RESUME = "resume"      // value = byte offset already stored
            const val TYPE_RETRANSMIT = "retransmit" // value = sequence number
            const val TYPE_PROGRESS = "progress"
            const val TYPE_DONE = "done"
            const val TYPE_BYE = "bye"
        }
    }
}

object LegacyPorts {
    const val CONTROL_PORT = 54321
    const val DATA_PORT = 54322
}
