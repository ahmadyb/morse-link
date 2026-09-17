package com.morselink.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per completed (or failed) file transfer, in either direction.
 * The history is strictly local — it never leaves the device.
 */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val direction: String,
    val peerName: String,
    val transport: String,
    val status: String,
    val timestamp: Long,
    val localPath: String?,
    val uriString: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        const val DIRECTION_SENT = "SENT"
        const val DIRECTION_RECEIVED = "RECEIVED"

        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
