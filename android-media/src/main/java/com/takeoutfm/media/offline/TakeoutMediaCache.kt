package com.takeoutfm.media.offline

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.takeoutfm.shared.music.QueueItem
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Whether a track is on this device. */
sealed interface CacheStatus {
    /** Downloading, [percent] 0–100 (or queued). */
    data class Partial(val percent: Int) : CacheStatus
    data object Cached : CacheStatus
}

/** What a download is, for the Downloads page (Media3 keeps it with the request). */
@Serializable
data class DownloadInfo(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val imageUri: String = "",
    val podcast: Boolean = false,
    /** The album, playlist or podcast it was downloaded with, and that group's name. */
    val group: String = "",
    val groupTitle: String = "",
)

/** A download as the app shows it. */
data class DownloadedItem(
    val item: QueueItem,
    val info: DownloadInfo,
    val status: CacheStatus,
    val bytes: Long,
)

/**
 * Music and podcast audio on the device: one Media3 [SimpleCache] keyed by the track's ETag
 * (stream URLs redirect to expiring signed URLs, so URLs can't be keys), filled by playback
 * while [cachingEnabled] says so ("Playback Caching"; off, nothing is written but cached
 * tracks still play from here) and by [download]. Nothing is evicted; [remove] frees space.
 * Live streams are never cached (they have no length).
 */
@OptIn(UnstableApi::class)
class TakeoutMediaCache(
    context: Context,
    /** The Takeout stream source (media token on the signed-in server). */
    upstream: DataSource.Factory,
    private val cachingEnabled: () -> Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val database = StandaloneDatabaseProvider(context)

    val cache: Cache = SimpleCache(File(context.filesDir, "media"), NoOpCacheEvictor(), database)

    private val readOnly = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS)

    private val readWrite = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS)

    /** For the music player: reads the cache, and writes to it while caching is on. */
    val playbackDataSourceFactory: DataSource.Factory =
        DataSource.Factory { Switching(readOnly.createDataSource(), readWrite.createDataSource(), cachingEnabled) }

    val downloads: DownloadManager = DownloadManager(
        context, database, cache, upstream, Executors.newFixedThreadPool(2),
    ).apply { maxParallelDownloads = 2 }

    private val _items = MutableStateFlow<Map<String, DownloadedItem>>(emptyMap())

    /** Every download by ETag, updated as they progress. */
    val items: StateFlow<Map<String, DownloadedItem>> = _items.asStateFlow()

    init {
        downloads.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                val item = download.toItem()
                _items.update { if (item == null) it - download.request.id else it + (download.request.id to item) }
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                _items.update { it - download.request.id }
            }

            override fun onInitialized(manager: DownloadManager) = reload()
        })
        if (downloads.isInitialized) reload()
    }

    /** The requests to add [items] (with ETags) as part of [group]; send them with the download service. */
    fun requests(items: List<QueueItem>, group: String, groupTitle: String): List<DownloadRequest> =
        items.filter { it.etag.isNotEmpty() && it.uri.isNotEmpty() }.map { item ->
            val info = DownloadInfo(item.title, item.artist, item.album, item.imageUri, item.podcast, group, groupTitle)
            DownloadRequest.Builder(item.etag, Uri.parse(item.uri))
                .setCustomCacheKey(item.etag)
                .setData(json.encodeToString(DownloadInfo.serializer(), info).toByteArray())
                .build()
        }

    /** Whether [etag] is wholly on the device (downloaded, or streamed through while caching). */
    fun isCached(etag: String): Boolean {
        if (etag.isEmpty()) return false
        _items.value[etag]?.let { return it.status == CacheStatus.Cached }
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(etag))
        return length > 0 && cache.isCached(etag, 0, length)
    }

    /** Bytes the cache holds in all (downloads and cached playback). */
    fun usedBytes(): Long = cache.cacheSpace

    private fun reload() {
        val all = mutableMapOf<String, DownloadedItem>()
        downloads.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                cursor.download.toItem()?.let { all[cursor.download.request.id] = it }
            }
        }
        _items.value = all
    }

    private fun Download.toItem(): DownloadedItem? {
        if (state == Download.STATE_FAILED || state == Download.STATE_REMOVING) return null
        val info = runCatching { json.decodeFromString(DownloadInfo.serializer(), String(request.data)) }
            .getOrDefault(DownloadInfo(title = request.id))
        val status = if (state == Download.STATE_COMPLETED) {
            CacheStatus.Cached
        } else {
            CacheStatus.Partial(percentDownloaded.takeIf { it >= 0 }?.toInt() ?: 0)
        }
        return DownloadedItem(
            item = QueueItem(
                title = info.title,
                artist = info.artist,
                album = info.album,
                imageUri = info.imageUri,
                uri = request.uri.toString(),
                etag = request.id,
                podcast = info.podcast,
            ),
            info = info,
            status = status,
            bytes = bytesDownloaded,
        )
    }

    /**
     * Opens the cache read-only or read-write, as [writing] says when playback opens a file,
     * so turning Playback Caching on or off applies to the next track.
     */
    private class Switching(
        private val read: DataSource,
        private val write: DataSource,
        private val writing: () -> Boolean,
    ) : DataSource {
        private var current: DataSource = read

        override fun addTransferListener(transferListener: TransferListener) {
            read.addTransferListener(transferListener)
            write.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            current = if (writing()) write else read
            return current.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current.read(buffer, offset, length)
        override fun getUri(): Uri? = current.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current.responseHeaders
        override fun close() = current.close()
    }
}

/** Bytes as "12 MB" / "1.4 GB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes / (1L shl 20))
    else -> "%d KB".format((bytes / 1024).coerceAtLeast(1))
}
