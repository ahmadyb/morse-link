package com.morselink.feature.send

import android.net.Uri
import com.morselink.core.transfer.model.TransferableFile
import javax.inject.Inject
import javax.inject.Singleton

/** Files shared in from another app (share sheet → Morselink). */
@Singleton
class SendArgs @Inject constructor() {
    @Volatile
    var externalUris: List<TransferableFile> = emptyList()
}

/** Selection survives tab switches inside the Send flow. */
@Singleton
class SendSelection @Inject constructor() {

    private val selected = LinkedHashMap<String, TransferableFile>()

    fun toggle(row: SendRow) {
        if (selected.containsKey(row.key)) selected.remove(row.key)
        else {
            selected[row.key] = TransferableFile(
                id = row.key,
                name = row.name,
                sizeBytes = row.sizeBytes,
                mimeType = row.mimeType,
                uri = row.uri,
                path = row.path,
            )
        }
    }

    fun isSelected(row: SendRow): Boolean = selected.containsKey(row.key)

    fun snapshot(): List<TransferableFile> = selected.values.toList()

    fun size(): Int = selected.size

    fun clear() = selected.clear()

    fun addExternal(files: List<TransferableFile>) {
        files.forEach { file -> selected[file.id] = file }
    }

    fun uris(): List<Uri> = selected.values.mapNotNull { it.uri }
}
