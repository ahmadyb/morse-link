package com.morselink.core.media

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for everything the UI reads off the device.
 * Smart-category counts are cached briefly so tab switching does not rescan
 * storage every time (§14.3).
 */
@Singleton
class MediaRepository @Inject constructor(
    private val mediaStore: MediaStoreDataSource,
    private val appScanner: AppScanner,
    private val fileBrowser: FileBrowser,
    private val fileOps: FileOps,
) {

    private var countsCache: Map<SmartCategory, Int>? = null
    private var countsTimestamp: Long = 0L

    suspend fun photos(sort: SortOrder = SortOrder.DATE): List<MediaItem> = mediaStore.photos(sort)
    suspend fun videos(sort: SortOrder = SortOrder.DATE): List<MediaItem> = mediaStore.videos(sort)
    suspend fun music(sort: SortOrder = SortOrder.DATE): List<MediaItem> = mediaStore.music(sort)
    suspend fun apps(): List<AppItem> = appScanner.installedApps()

    suspend fun listDirectory(path: String): List<FileItem> = fileBrowser.listDirectory(path)
    suspend fun category(category: SmartCategory): List<FileItem> = fileBrowser.category(category)

    suspend fun categoryCounts(force: Boolean = false): Map<SmartCategory, Int> {
        val cached = countsCache
        if (!force && cached != null && System.currentTimeMillis() - countsTimestamp < CACHE_TTL_MS) {
            return cached
        }
        val counts = fileBrowser.counts()
        countsCache = counts
        countsTimestamp = System.currentTimeMillis()
        return counts
    }

    /** §15.3 — the counters shown on the WebShare home view. */
    suspend fun categoryTotals(): Map<String, Int> = coroutineScope {
        val photos = async { runCatching { photos().size }.getOrDefault(0) }
        val videos = async { runCatching { videos().size }.getOrDefault(0) }
        val music = async { runCatching { music().size }.getOrDefault(0) }
        val apps = async { runCatching { apps().size }.getOrDefault(0) }
        val documents = async { runCatching { category(SmartCategory.DOCUMENTS).size }.getOrDefault(0) }
        mapOf(
            "photos" to photos.await(),
            "videos" to videos.await(),
            "music" to music.await(),
            "apps" to apps.await(),
            "documents" to documents.await(),
        )
    }

    suspend fun storageInfo(): StorageInfo = fileOps.storageInfo()

    fun usableSpace(): Long = fileOps.usableSpace()
    fun defaultDownloadDirectory() = fileOps.defaultDownloadDirectory()

    companion object {
        private const val CACHE_TTL_MS = 30_000L
    }
}
