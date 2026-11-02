@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.linux

import com.takeoutfm.shared.api.HttpException
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.store.FileStore
import com.takeoutfm.shared.store.PosixLock
import com.takeoutfm.shared.video.VideoDetails
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.refTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import platform.posix.EEXIST
import platform.posix.F_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.rename
import platform.posix.unlink

/** One downloaded (or downloading) file, as the index keeps it. */
@Serializable
data class DownloadEntry(
    val etag: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val imageUri: String = "",
    val podcast: Boolean = false,
    /** A movie or episode; [group] is then its id ("m-12", "e-34"). */
    val video: Boolean = false,
    /** The Takeout location it came from. */
    val location: String,
    /** The album, playlist, podcast or title it was downloaded with, and its name. */
    val group: String,
    val groupTitle: String,
    /** In the media directory, once it's all here. */
    val fileName: String? = null,
    val bytes: Long = 0,
)

@Serializable
enum class DownloadState { Queued, Downloading, Done, Failed }

/** A download as the app sees it: what it is, how it's going, and its file once done. */
@Serializable
data class Download(
    val entry: DownloadEntry,
    val state: DownloadState,
    /** 0–1 while downloading. */
    val fraction: Float = 0f,
    /** The file's full path when [state] is Done. */
    val path: String? = null,
)

/**
 * Downloads on this computer, as the Mac's MediaCache: whole media files in [directory]/media,
 * keyed by ETag (stream URLs redirect to presigned, expiring ones, so a URL is no key), with
 * an index of what each is and the album, playlist, podcast or title it came with. They stay
 * until removed; playback reads them first, so they play offline; live streams (no ETag) are
 * never kept. A download asks Takeout for its presigned URL with the media token, then fetches
 * that without it (storage refuses a second kind of authorization); two at a time. Downloads
 * interrupted when the app quit start again.
 */
class MediaDownloads internal constructor(
    directory: String,
    private val session: TakeoutSession,
    private val userAgent: String,
    engine: HttpClientEngine,
) {
    private val media = "$directory/media"
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val slots = Semaphore(Parallel)
    private val jobs = mutableMapOf<String, Job>()
    private val lock = PosixLock()

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads: StateFlow<Map<String, Download>> = _downloads.asStateFlow()

    private val index: FileStore

    init {
        if (mkdir(media, 0b111_000_000u) != 0 && errno != EEXIST) throw IOException("can't create $media")
        index = FileStore(media, "downloads")
        val saved = index.getString(IndexKey)?.let {
            runCatching { bridgeJson.decodeFromString(ListSerializer(DownloadEntry.serializer()), it) }.getOrNull()
        }.orEmpty()
        _downloads.value = saved.associate { entry ->
            val path = entry.fileName?.let { "$media/$it" }?.takeIf { access(it, F_OK) == 0 }
            entry.etag to if (path != null) Download(entry, DownloadState.Done, 1f, path) else Download(entry.copy(fileName = null, bytes = 0), DownloadState.Queued)
        }
        // Interrupted last time: start again.
        _downloads.value.values.filter { it.state == DownloadState.Queued }.forEach { start(it.entry.etag) }
    }

    /** The downloaded file for [etag], if it's wholly here. */
    fun localPath(etag: String): String? = _downloads.value[etag]?.takeIf { it.state == DownloadState.Done }?.path

    /** Downloads [queue]'s items (all, or [only] those), grouped with the queue. */
    fun download(queue: MusicQueue, only: List<QueueItem>? = null) =
        download(only ?: queue.items, queue.location.ifEmpty { "title:${queue.title}" }, queue.title)

    /** Downloads [items] (a queue's tracks or episodes) as part of [group]. */
    fun download(items: List<QueueItem>, group: String, groupTitle: String) {
        add(
            items.filter { it.etag.isNotEmpty() }.map {
                DownloadEntry(
                    etag = it.etag, title = it.title, artist = it.artist, album = it.album, imageUri = it.imageUri,
                    podcast = it.podcast, location = it.uri, group = group, groupTitle = groupTitle,
                )
            },
        )
    }

    /** Downloads a movie or episode. */
    fun download(details: VideoDetails) {
        if (details.etag.isEmpty() || details.videoUri.isEmpty()) return
        add(
            listOf(
                DownloadEntry(
                    etag = details.etag, title = details.title, imageUri = details.backdropUri, video = true,
                    location = details.videoUri, group = details.id, groupTitle = details.title,
                ),
            ),
        )
    }

    /** Downloads a failed entry again, as it was. */
    fun retry(etag: String) {
        val download = _downloads.value[etag]?.takeIf { it.state == DownloadState.Failed } ?: return
        set(download.copy(state = DownloadState.Queued, fraction = 0f))
        start(etag)
    }

    fun remove(etags: Collection<String>) {
        for (etag in etags) {
            lock.locked { jobs.remove(etag) }?.cancel()
            _downloads.value[etag]?.path?.let { unlink(it) }
            _downloads.update { it - etag }
        }
        save()
    }

    fun removeAll() = remove(_downloads.value.keys.toList())

    private fun add(entries: List<DownloadEntry>) {
        val added = entries.filter { entry ->
            val existing = _downloads.value[entry.etag]
            existing == null || existing.state == DownloadState.Failed
        }
        if (added.isEmpty()) return
        _downloads.update { all -> all + added.associate { it.etag to Download(it, DownloadState.Queued) } }
        save()
        added.forEach { start(it.etag) }
    }

    private fun start(etag: String) {
        val job = scope.launch {
            slots.withPermit {
                val entry = _downloads.value[etag]?.entry ?: return@withPermit
                try {
                    fetch(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("TakeoutFM: download ${entry.title} failed: ${e.message}")
                    _downloads.value[etag]?.let { set(it.copy(state = DownloadState.Failed)) }
                }
            }
            lock.locked { jobs.remove(etag) }
        }
        lock.locked { jobs[etag] = job }
    }

    /** Fetches [entry] into a partial file, then renames it into place. */
    private suspend fun fetch(entry: DownloadEntry) {
        var url = entry.location
        // The media token, only to Takeout; never to storage it redirects to.
        var authorization = session.mediaAuthorization(url)
        val partial = "$media/${safeName(entry.etag)}.part"
        repeat(MaxRedirects) {
            val next: String? = client.prepareGet(url) {
                header(HttpHeaders.UserAgent, userAgent)
                authorization?.let { header(HttpHeaders.Authorization, it) }
            }.execute { response ->
                val status = response.status.value
                when {
                    status in 300..399 -> {
                        val location = response.headers[HttpHeaders.Location] ?: throw HttpException(status)
                        URLBuilder(url).takeFrom(location).buildString()
                    }
                    status !in 200..299 -> throw HttpException(status)
                    else -> {
                        set(_downloads.value[entry.etag]?.copy(state = DownloadState.Downloading) ?: return@execute null)
                        val bytes = write(response.bodyAsChannel(), partial, response.contentLength() ?: 0L, entry.etag)
                        // Keep the file's own extension (flac, mp3, mkv…), which players go by.
                        val extension = Url(url).encodedPath.substringAfterLast('/').substringAfterLast('.', "")
                            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "media"
                        val name = "${safeName(entry.etag)}.$extension"
                        rename(partial, "$media/$name")
                        val done = entry.copy(fileName = name, bytes = bytes)
                        if (_downloads.value.containsKey(entry.etag)) {
                            // Saved before it's announced, so whoever sees it done finds it in the index.
                            val finished = Download(done, DownloadState.Done, 1f, "$media/$name")
                            save(_downloads.value + (entry.etag to finished))
                            set(finished)
                        } else {
                            unlink("$media/$name") // removed while it finished
                        }
                        null
                    }
                }
            }
            if (next == null) return
            url = next
            authorization = null
        }
        throw IOException("too many redirects")
    }

    /** Streams [channel] to [path], reporting progress against [length]; the byte count. */
    private suspend fun write(channel: io.ktor.utils.io.ByteReadChannel, path: String, length: Long, etag: String): Long {
        val file = fopen(path, "wb") ?: throw IOException("can't write $path")
        var total = 0L
        var reported = 0f
        try {
            val buffer = ByteArray(BufferSize)
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) continue
                if (fwrite(buffer.refTo(0), 1u, n.toULong(), file).toInt() != n) throw IOException("can't write $path")
                total += n
                if (length > 0) {
                    val fraction = total.toFloat() / length
                    if (fraction - reported >= 0.01f) {
                        reported = fraction
                        _downloads.value[etag]?.let { set(it.copy(fraction = fraction)) }
                    }
                }
            }
        } catch (e: Throwable) {
            fclose(file)
            unlink(path)
            throw e
        }
        fclose(file)
        return total
    }

    private fun set(download: Download) {
        _downloads.update { all -> if (all.containsKey(download.entry.etag)) all + (download.entry.etag to download) else all }
    }

    private fun save(downloads: Map<String, Download> = _downloads.value) {
        val entries = downloads.values.map { it.entry }
        index.putString(IndexKey, bridgeJson.encodeToString(ListSerializer(DownloadEntry.serializer()), entries))
    }

    private fun safeName(etag: String) = etag.filter { it.isLetterOrDigit() || it == '-' }

    private companion object {
        const val Parallel = 2
        const val MaxRedirects = 5
        const val BufferSize = 64 * 1024
        const val IndexKey = "entries"
    }
}
