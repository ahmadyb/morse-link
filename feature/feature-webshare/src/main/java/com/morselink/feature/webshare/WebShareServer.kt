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

    private fun upload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            val results = JSONArray()
            val directory = fileOps.defaultDownloadDirectory()
            files.forEach { (field, tempPath) ->
                val originalName = session.parms[field] ?: File(tempPath).name
                val target = File(directory, sanitize(originalName))
                val temp = File(tempPath)
                runCatching {
                    temp.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    temp.delete()
                    runBlocking {
                        fileOps.publishToMediaStore(
                            target,
                            android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(target.extension.lowercase(Locale.US)),
                        )
                    }
                }
                results.put(JSONObject().apply {
                    put("name", target.name)
                    put("size", target.length())
                    put("saved", target.exists())
                })
            }
            json(JSONObject().apply {
                put("uploaded", results.length())
                put("files", results)
            }.toString())
        } catch (error: Exception) {
            json(JSONObject().apply {
                put("error", error.message ?: "Upload failed")
            }.toString())
        }
    }

    private fun renameFile(session: IHTTPSession): String {
        val path = session.parms["path"] ?: return errorJson("Missing path")
        val name = session.parms["name"] ?: return errorJson("Missing name")
        val result = runBlocking { runCatching { fileOps.rename(File(path), name) } }
        return JSONObject().apply {
            put("ok", result.isSuccess)
            if (result.isFailure) put("error", result.exceptionOrNull()?.message)
        }.toString()
    }

    private fun deleteFile(session: IHTTPSession): String {
        val path = session.parms["path"] ?: return errorJson("Missing path")
        val file = File(path)
        val result = runBlocking {
            runCatching {
                fileOps.delete(
                    com.morselink.core.media.FileItem(
                        path = file.absolutePath,
                        name = file.name,
                        isDirectory = file.isDirectory,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        mimeType = null,
                    )
                )
            }
        }
        return JSONObject().apply {
            put("ok", result.isSuccess)
            if (result.isFailure) put("error", result.exceptionOrNull()?.message)
        }.toString()
    }

    private fun stopSession(): String {
        onStopRequest?.invoke()
        return JSONObject().apply { put("ok", true) }.toString()
    }

    private fun errorJson(message: String): String =
        JSONObject().apply { put("error", message) }.toString()

    /** Called by the browser's "end session" button. */
    var onStopRequest: (() -> Unit)? = null

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
    }
}
