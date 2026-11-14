@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.linux

import com.takeoutfm.shared.offline.CacheEntry
import com.takeoutfm.shared.offline.CacheLimit
import com.takeoutfm.shared.offline.CachePolicy
import com.takeoutfm.shared.offline.RoomPlan
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.progress.TakeoutProgress
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import platform.linux.statvfs

/** The downloads after a purge, for the settings and Downloads pages (`cacheUsage`). */
@Serializable
data class CacheUsage(
    /** What Playback Caching keeps. */
    val cachedBytes: Long,
    /** What the viewer downloaded. */
    val downloadedBytes: Long,
    /** What Playback Caching may keep now. */
    val budgetBytes: Long,
    /** The viewer's downloads (or other programs) leave too little free: warn. */
    val lowSpace: Boolean,
    /** Downloaded movies and episodes watched to their end: offered for removal. */
    val watched: List<String>,
    /** The size set: 0 for Auto, else bytes. */
    val limitBytes: Long,
    /** The sizes to choose from (0 for Auto), and what each comes to here. */
    val choices: List<CacheChoice>,
)

@Serializable
data class CacheChoice(val limitBytes: Long, val bytes: Long)

/** What `downloadVideo` did: started it, or found too little room (and asks first). */
@Serializable
data class VideoDownload(val started: Boolean, val needBytes: Long = 0, val freeableBytes: Long = 0, val fits: Boolean = true)

/**
 * Keeps what Playback Caching downloaded within its size (`CachePolicy`, PLAN.md §4.11), as on
 * Android: the purge order is the shared one (finished podcast episodes, then the least
 * recently played) and the viewer's own downloads are never removed. Runs at start, when
 * downloads change (a track kept) and when the size changes; [clearCached] empties it. What's
 * playing stays (Linux would keep an open file anyway, but the next track mpv loads wouldn't).
 */
class LinuxCachePurge(
    private val downloads: MediaDownloads,
    private val limit: StateFlow<CacheLimit>,
    private val progress: TakeoutProgress,
    private val playing: () -> String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _usage = MutableStateFlow<CacheUsage?>(null)

    /** How the downloads stand after the latest purge (null before the first). */
    val usage: StateFlow<CacheUsage?> = _usage.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            merge(
                // only when what's done changes, not at every percent of a download
                downloads.downloads.map { all -> all.values.filter { it.state == DownloadState.Done }.map { it.entry.etag } }
                    .distinctUntilChanged().drop(1).debounce(DebounceMs).map { },
                limit.map { },
            ).collect { purge() }
        }
    }

    /** Purges and recounts now (the Downloads page opening: what's been watched since). */
    fun refresh() {
        scope.launch { purge() }
    }

    /** Removes everything Playback Caching kept (not the viewer's downloads). */
    fun clearCached() {
        scope.launch { purge(clear = true) }
    }

    /**
     * Room for a download of [bytes] (a movie): cached entries go first if that's needed; if
     * even that isn't enough, nothing goes and [RoomPlan.enough] is false, so the app asks.
     */
    suspend fun makeRoom(bytes: Long): RoomPlan = lock.withLock {
        val (total, free) = volume()
        if (bytes <= 0 || total <= 0) return@withLock RoomPlan(emptyList(), enough = true, freeableBytes = 0)
        val plan = CachePolicy.makeRoom(entries(finished()), total, free, bytes)
        downloads.remove(plan.remove)
        plan
    }

    private suspend fun purge(clear: Boolean = false) = lock.withLock {
        try {
            val finished = finished()
            val entries = entries(finished)
            val (total, free) = volume()
            // Not knowing the volume would make the budget nothing and empty the cache.
            if (total <= 0) return@withLock
            val remove = if (clear) {
                entries.filter { it.cached }.map { it.etag }
            } else {
                CachePolicy.plan(entries, limit.value, total, free).remove
            }
            if (remove.isNotEmpty()) println("TakeoutFM: cache purge removes ${remove.size} of ${entries.size}")
            downloads.remove(remove)
            val (total2, free2) = volume()
            val after = entries(finished)
            val plan = CachePolicy.plan(after, limit.value, total2, free2)
            _usage.value = CacheUsage(
                cachedBytes = after.filter { it.cached }.sumOf { it.bytes },
                downloadedBytes = plan.downloadedBytes,
                budgetBytes = plan.budgetBytes,
                lowSpace = plan.lowSpace,
                watched = finished.videos.toList(),
                limitBytes = (limit.value as? CacheLimit.Fixed)?.bytes ?: 0,
                choices = CachePolicy.Choices.map { c ->
                    CacheChoice((c as? CacheLimit.Fixed)?.bytes ?: 0, CachePolicy.limitBytes(c, total2))
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("TakeoutFM: cache purge failed: ${e.message}")
        }
    }

    /** Every finished download (the one playing as the viewer's, so it stays). */
    private fun entries(finished: Finished): List<CacheEntry> {
        val current = playing()
        return downloads.downloads.value.values.filter { it.state == DownloadState.Done }.map { d ->
            val e = d.entry
            CacheEntry(
                etag = e.etag,
                bytes = e.bytes,
                lastUsedMs = e.lastUsedMs,
                cached = e.cached && e.etag != current,
                finishedPodcast = e.podcast && e.etag in finished.episodes,
            )
        }
    }

    /** Downloaded podcast episodes (by GUID) and the viewer's videos (by ETag) played to their end. */
    private class Finished(val episodes: Set<String>, val videos: Set<String>)

    private suspend fun finished(): Finished {
        val done = downloads.downloads.value.values.filter { it.state == DownloadState.Done }.map { it.entry }
        val episodes = done.filter { it.podcast }.map { it.etag }
        val videos = done.filter { it.video && !it.cached }.map { it.etag }
        if (episodes.isEmpty() && videos.isEmpty()) return Finished(emptySet(), emptySet())
        val latest = try {
            progress.latest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        fun finished(etags: List<String>, endPercent: Int) =
            etags.filter { latest[it]?.let { o -> ProgressRules.isFinished(o, endPercent) } == true }.toSet()
        return Finished(finished(episodes, ProgressRules.PodcastEndPercent), finished(videos, ProgressRules.VideoEndPercent))
    }

    /** The downloads volume's size and free space, in bytes. */
    private fun volume(): Pair<Long, Long> = memScoped {
        val stat = alloc<statvfs>()
        if (statvfs(downloads.directory, stat.ptr) != 0) return 0L to 0L
        val block = stat.f_frsize.toLong()
        (stat.f_blocks.toLong() * block) to (stat.f_bavail.toLong() * block)
    }

    private companion object {
        const val DebounceMs = 3_000L
    }
}
