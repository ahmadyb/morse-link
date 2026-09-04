package com.morselink.feature.webshare

import android.content.Context
import com.morselink.core.media.AppItem
import com.morselink.core.media.FileItem
import com.morselink.core.media.FileOps
import com.morselink.core.media.MediaItem
import com.morselink.core.media.MediaRepository
import com.morselink.core.media.SmartCategory
import com.morselink.core.data.prefs.SettingsStore
import com.morselink.core.data.prefs.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §4.5 — the WebShare HTTP server. Self-contained HTML with no CDN dependency,
 * JSON listing API, Range-capable downloads and multipart uploads.
 */
@Singleton
class WebShareServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media: MediaRepository,
    private val fileOps: FileOps,
    private val settings: SettingsStore,
) : NanoHTTPD(PORT) {

    private val entries = LinkedHashMap<String, Entry>()

    /** Invoked when a browser asks the server to stop, so the view model can
     *  also release the hotspot and the foreground service. */
    var onStopRequest: (() -> Unit)? = null

    data class Entry(val path: String, val name: String, val size: Long, val mime: String)

    fun startServer(): Boolean = runCatching { start(SOCKET_READ_TIMEOUT, false); true }.getOrDefault(false)

    fun stopServer() = runCatching { stop() }

    fun index(): String = runCatching {
        context.resources.openRawResource(R.raw.webshare).bufferedReader().use { it.readText() }
    }.getOrDefault("<html><body><h1>Morselink</h1><p>Interface failed to load.</p></body></html>")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        return when {
            session.method == Method.OPTIONS -> cors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
            session.method == Method.GET && (uri == "" || uri == "/") ->
                newFixedLengthResponse(Response.Status.OK, MIME_HTML, index())
            session.method == Method.GET && uri.startsWith("/api/files") -> json(filesResponse(session))
            session.method == Method.GET && uri.startsWith("/api/info") -> json(infoResponse())
            session.method == Method.GET && uri.startsWith("/api/theme") -> json(themeResponse())
            session.method == Method.GET && uri.startsWith("/api/rename") -> json(renameFile(session))
            session.method == Method.GET && uri.startsWith("/api/delete") -> json(deleteFile(session))
            session.method == Method.GET && uri.startsWith("/api/stop") -> json(stopSession())
            session.method == Method.GET && uri.startsWith("/download") -> download(session)
            session.method == Method.POST && uri.startsWith("/upload") -> upload(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    // ------------------------------------------------------------------ listings

    private fun filesResponse(session: IHTTPSession): String {
        val category = session.parms["category"] ?: "photos"
        val path = session.parms["path"]
        val array = JSONArray()
        when (category) {
            "photos", "videos", "music" -> {
                val items: List<MediaItem> = runBlocking {
                    when (category) {
                        "videos" -> media.videos()
                        "music" -> media.music()
                        else -> media.photos()
                    }
                }
                items.forEach { item ->
                    val id = "${category}:${item.id}"
                    entries[id] = Entry(
                        path = item.path ?: "",
                        name = item.displayName,
                        size = item.sizeBytes,
                        mime = item.mimeType ?: "application/octet-stream",
                    )
                    array.put(jsonFor(id, item.displayName, item.sizeBytes, item.mimeType ?: "",
                        extra = JSONObject().apply {
                            put("uri", item.uri.toString())
                            put("date", item.dateModified)
                            if (item.durationMs > 0) put("duration", item.durationMs)
                            item.artist?.let { put("artist", it) }
                        }))
                }
            }
            "apps" -> {
                val apps: List<AppItem> = runBlocking { media.apps() }
                apps.forEach { app ->
                    val id = "apps:${app.packageName}"
                    entries[id] = Entry(app.apkPath, "${app.label}.apk", app.sizeBytes, APK_MIME)
                    array.put(jsonFor(id, "${app.label}.apk", app.sizeBytes, APK_MIME,
                        extra = JSONObject().apply { put("package", app.packageName) }))
                }
            }
            else -> {
                val files: List<FileItem> = runBlocking {
                    if (path.isNullOrBlank()) media.category(SmartCategory.DOCUMENTS)
                    else media.listDirectory(path)
                }
                files.forEach { file ->
                    val id = "file:${file.path}"
                    entries[id] = Entry(file.path, file.name, file.sizeBytes, file.mimeType ?: "")
                    array.put(jsonFor(id, file.name, file.sizeBytes, file.mimeType ?: "",
                        extra = JSONObject().apply {
                            put("isDirectory", file.isDirectory)
                            put("modified", file.lastModified)
                            put("path", file.path)
                        }))
                }
            }
        }
        return JSONObject().apply {
            put("category", category)
            put("path", path ?: "")
            put("files", array)
        }.toString()
    }

    private fun jsonFor(
        id: String,
        name: String,
        size: Long,
        mime: String,
        extra: JSONObject? = null,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("size", size)
        put("mime", mime)
        if (extra != null) {
            for (key in extra.keys()) put(key, extra.get(key))
        }
    }

    private fun infoResponse(): String {
        val totals = runBlocking { media.categoryTotals() }
        val storage = runBlocking { media.storageInfo() }
        val theme = runBlocking { settings.current() }
        return JSONObject().apply {
            put("device", theme.deviceName)
            put("android", "Android ${android.os.Build.VERSION.RELEASE}")
            put("version", "1.0.0")
            put("counts", JSONObject().apply {
                totals.forEach { (key, value) -> put(key, value) }
            })
            put("storage", JSONObject().apply {
                put("used", storage.usedBytes)
                put("total", storage.totalBytes)
            })
        }.toString()
    }

    private fun themeResponse(): String {
        val mode = runBlocking { settings.current().themeMode }
        return JSONObject().apply {
            put("theme", when (mode) {
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
                ThemeMode.SYSTEM -> if (isSystemDark()) "dark" else "light"
            })
        }.toString()
    }

    private fun isSystemDark(): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // ------------------------------------------------------------- file actions

    private fun errorJson(message: String): String =
        JSONObject().apply {
            put("ok", false)
            put("error", message)
        }.toString()

    private fun renameFile(session: IHTTPSession): String {
        val path = session.parms["path"] ?: return errorJson("Missing path")
        val name = session.parms["name"]?.trim().orEmpty()
        if (name.isEmpty()) return errorJson("Missing new name")
        val file = File(path)
        if (!file.exists()) return errorJson("That file no longer exists")
        return runBlocking {
            runCatching { fileOps.rename(file, name) }.fold(
                onSuccess = { renamed ->
                    JSONObject().apply {
                        put("ok", true)
                        put("name", renamed.name)
                        put("path", renamed.absolutePath)
                    }.toString()
                },
                onFailure = { errorJson(it.message ?: "Rename failed") },
            )
        }
    }

    private fun deleteFile(session: IHTTPSession): String {
        val path = session.parms["path"] ?: return errorJson("Missing path")
        val file = File(path)
        if (!file.exists()) return errorJson("That file no longer exists")
        val item = FileItem(
            path = file.absolutePath,
            name = file.name,
            isDirectory = file.isDirectory,
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            mimeType = null,
            childCount = 0,
        )
        return runBlocking {
            runCatching { fileOps.delete(item) }.fold(
                onSuccess = { JSONObject().apply { put("ok", true) }.toString() },
                onFailure = { errorJson(it.message ?: "Delete failed") },
            )
        }
    }

    /**
     * Answers first, then tears the server down: stopping inside [serve] would
     * kill the socket before the response is flushed. [onStopRequest] lets the
     * view model drop the hotspot and the foreground service too.
     */
    private fun stopSession(): String {
        Thread {
            runCatching { Thread.sleep(250) }
            runCatching { stopServer() }
            runCatching { onStopRequest?.invoke() }
        }.apply { isDaemon = true }.start()
        return JSONObject().apply { put("ok", true) }.toString()
    }

    // ------------------------------------------------------------------ download

    private fun download(session: IHTTPSession): Response {
        val id = session.parms["id"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing id")
        val entry = entries[id]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown file")
        val file = File(entry.path)
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File unavailable")
        }
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]
        var start = 0L
        var end = file.length() - 1
        var status = Response.Status.OK
        if (!rangeHeader.isNullOrBlank()) {
            val match = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader)
            if (match != null) {
                val from = match.groupValues[1].toLongOrNull()
                val to = match.groupValues[2].toLongOrNull()
                start = from ?: (file.length() - (to ?: 0))
                end = to ?: (file.length() - 1)
                status = Response.Status.PARTIAL_CONTENT
            }
        }
        val length = (end - start + 1).coerceAtLeast(0)
        val stream = FileInputStream(file).apply { skip(start) }
        val response = newFixedLengthResponse(status, entry.mime.ifBlank { "application/octet-stream" }, stream, length)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", length.toString())
        if (status == Response.Status.PARTIAL_CONTENT) {
            response.addHeader("Content-Range", "bytes $start-$end/${file.length()}")
        }
        response.addHeader("Content-Disposition", "attachment; filename=\"${entry.name}\"")
        return cors(response)
    }

    // -------------------------------------------------------------------- upload

    /**
     * Streaming multipart upload: the body is scanned for the boundary and each
     * part is streamed straight to disk, so a 2 GB upload never sits in RAM.
     */
    private fun upload(session: IHTTPSession): Response {
        val contentType = session.headers["content-type"]
            ?: session.headers["Content-Type"]
            ?: return json(errorJson("Expected multipart/form-data"))
        val boundary = Regex("""boundary=([^;\s]+)""").find(contentType)
            ?.groupValues?.get(1)?.trim('"')
            ?: return json(errorJson("Missing multipart boundary"))

        val marker = "--$boundary".toByteArray(Charsets.UTF_8)
        val results = JSONArray()
        val directory = fileOps.defaultDownloadDirectory()

        runCatching {
            val pushback = java.io.PushbackInputStream(session.inputStream, 8192)
            readUntilBoundary(pushback, marker)   // skip the preamble
            while (true) {
                val partHeaders = readHeaders(pushback) ?: break
                val name = Regex("""filename\*?="([^"]*)""").find(partHeaders)
                    ?.groupValues?.get(1)
                    ?: Regex("""filename\*?=([^;\r\n]+)""").find(partHeaders)
                        ?.groupValues?.get(1)?.trim()?.trim('"')
                val target = if (name.isNullOrBlank()) null else File(directory, sanitize(name))
                if (target == null) {
                    if (!readUntilBoundary(pushback, marker)) break
                    continue
                }
                FileOutputStream(target).use { out ->
                    if (!readUntilBoundary(pushback, marker, out)) return@use
                }
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(target.extension.lowercase(Locale.US))
                runBlocking { fileOps.publishToMediaStore(target, mime) }
                results.put(JSONObject().apply {
                    put("name", target.name)
                    put("size", target.length())
                    put("saved", target.exists())
                })
                if (isFinalBoundary(pushback)) break
            }
        }.onFailure { error ->
            return json(errorJson(error.message ?: "Upload failed"))
        }

        return json(JSONObject().apply {
            put("uploaded", results.length())
            put("files", results)
        }.toString())
    }

    /** Copies bytes to [sink] until the boundary is seen; returns false on EOF. */
    private fun readUntilBoundary(
        input: java.io.PushbackInputStream,
        marker: ByteArray,
        sink: java.io.OutputStream? = null,
    ): Boolean {
        val buffer = ByteArray(BUFFER_SIZE)
        var pending = 0
        while (true) {
            if (pending == buffer.size) {
                val keep = marker.size - 1
                sink?.write(buffer, 0, pending - keep)
                System.arraycopy(buffer, pending - keep, buffer, 0, keep)
                pending = keep
            }
            val read = input.read(buffer, pending, buffer.size - pending)
            if (read < 0) {
                sink?.write(buffer, 0, pending)
                return false
            }
            pending += read
            val index = indexOf(buffer, pending, marker)
            if (index >= 0) {
                sink?.write(buffer, 0, index)
                val consumed = index + marker.size
                val leftover = pending - consumed
                if (leftover > 0) input.unread(buffer, consumed, leftover)
                return true
            }
            val safe = pending - (marker.size - 1)
            if (safe > 0) {
                sink?.write(buffer, 0, safe)
                System.arraycopy(buffer, safe, buffer, 0, pending - safe)
                pending -= safe
            }
        }
    }

    private fun readHeaders(input: java.io.PushbackInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            builder.append(byte.toChar())
            if (builder.length >= HEADER_END.length &&
                builder.substring(builder.length - HEADER_END.length) == HEADER_END
            ) {
                return builder.toString()
            }
        }
    }

    /** True when the last boundary was the closing "--boundary--". */
    private fun isFinalBoundary(input: java.io.PushbackInputStream): Boolean {
        val first = input.read()
        val second = input.read()
        if (second >= 0) input.unread(second)
        if (first >= 0) input.unread(first)
        return first == '-'.code && second == '-'.code
    }

    private fun indexOf(buffer: ByteArray, length: Int, pattern: ByteArray): Int {
        if (pattern.isEmpty() || length < pattern.size) return -1
        val last = length - pattern.size
        for (i in 0..last) {
            if (buffer[i] != pattern[0]) continue
            var matched = true
            for (j in 1 until pattern.size) {
                if (buffer[i + j] != pattern[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "upload.bin" }

    // --------------------------------------------------------------------- utils

    private fun json(body: String): Response =
        cors(newFixedLengthResponse(Response.Status.OK, "application/json", body))

    private fun cors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type")
    }

    companion object {
        const val PORT = 33455
        private const val SOCKET_READ_TIMEOUT = 20_000
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val HEADER_END = "\r\n\r\n"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
