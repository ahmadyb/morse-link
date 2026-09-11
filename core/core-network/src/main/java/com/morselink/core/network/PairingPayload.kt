package com.morselink.core.network

import org.json.JSONObject

/**
 * What the sender puts in its QR code and the receiver reads back out (§14.8).
 *
 * Shared by both sides so the two halves of pairing can never drift apart.
 */
data class PairingPayload(
    val name: String,
    val address: String,
    val port: Int,
    val transport: String,
) {

    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("ip", address)
        put("port", port)
        put("transport", transport)
    }.toString()

    companion object {
        const val DEFAULT_PORT = 54321

        fun parse(raw: String): PairingPayload? = runCatching {
            val json = JSONObject(raw)
            val address = json.optString("ip")
            if (address.isBlank()) return@runCatching null
            PairingPayload(
                name = json.optString("name").ifBlank { "Morselink" },
                address = address,
                port = json.optInt("port", DEFAULT_PORT),
                transport = json.optString("transport").ifBlank { "LEGACY_WIFI_DIRECT" },
            )
        }.getOrNull()
    }
}
