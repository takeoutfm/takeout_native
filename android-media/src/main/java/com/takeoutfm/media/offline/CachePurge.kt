package com.takeoutfm.media.offline

import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.takeoutfm.shared.offline.CacheEntry
import com.takeoutfm.shared.offline.CacheLimit
import com.takeoutfm.shared.offline.CachePolicy
import com.takeoutfm.shared.offline.RoomPlan
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.progress.TakeoutProgress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The media store after a purge, for settings and the Downloads page. */
data class CacheUsage(
    /** What Playback Caching keeps (complete tracks and partial ones). */
    val cachedBytes: Long,
    /** What the viewer downloaded. */
    val downloadedBytes: Long,
    /** What Playback Caching may keep now. */
    val budgetBytes: Long,
    /** The viewer's downloads (or other apps) leave too little free: warn. */
    val lowSpace: Boolean,
    /** Downloaded movies and episodes watched to their end: offered for removal. */
    val watched: Set<String> = emptySet(),
)

/**
 * Keeps what Playback Caching added within its budget (`CachePolicy`, PLAN.md §4.11): the
 * whole store is listed — downloads, and tracks only partly streamed — and what the shared rule
 * picks is removed. The viewer's own downloads are never removed. Runs when [start]ed, when
 * downloads change (a track kept, say) and when the limit changes; [clearCached] empties it.
 */
@OptIn(UnstableApi::class)
class CachePurge(
    private val media: TakeoutMediaCache,
    private val limit: StateFlow<CacheLimit>,
    private val progress: TakeoutProgress,
    /** The ETags the music player has loaded (what's playing or paused, the queue's next): kept. */
    private val inPlayer: () -> Set<String>,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val _usage = MutableStateFlow<CacheUsage?>(null)

    /** How the store stands, after the latest purge (null before the first). */
    val usage: StateFlow<CacheUsage?> = _usage.asStateFlow()

    private var started = false

    /** Purges now, and whenever downloads or the limit change (once is enough). */
    @kotlin.OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // the index read (or already), downloads changing, the limit changing
            merge(media.ready.map { }, media.items.drop(1).debounce(DebounceMs).map { }, limit.map { })
                .collect { purge() }
        }
    }

    /** Purges and recounts now (the Downloads page opening: what's been watched since). */
    fun refresh() {
        scope.launch { purge() }
    }

    /** What [limit] comes to on this device (Auto: a share of the volume). */
    fun limitBytes(limit: CacheLimit): Long = CachePolicy.limitBytes(limit, StatFs(media.directory.path).totalBytes)

    /** Removes everything Playback Caching kept (not the viewer's downloads). */
    fun clearCached() {
        scope.launch { purge(clear = true) }
    }

    /**
     * Room for a download of [bytes] (a movie): cached entries go first if that's needed; if
     * even that isn't enough, nothing is removed and [RoomPlan.enough] is false, so the app asks.
     */
    suspend fun makeRoom(bytes: Long): RoomPlan = lock.withLock {
        if (!media.ready.value || bytes <= 0) return@withLock RoomPlan(emptyList(), enough = true, freeableBytes = 0)
        val finished = finished()
        val (entries, inUse) = withContext(Dispatchers.IO) { entries(finished) }
        val stat = StatFs(media.directory.path)
        val plan = CachePolicy.makeRoom(
            entries.map { if (it.etag in inUse) it.copy(cached = false) else it },
            stat.totalBytes, stat.availableBytes, bytes,
        )
        if (plan.remove.isNotEmpty()) Log.i(Tag, "removing ${plan.remove.size} cached for a ${bytes}-byte download")
        remove(plan.remove)
        plan
    }

    private suspend fun purge(clear: Boolean = false) = lock.withLock {
        // Before the download index is read, every download would look like a stray part.
        if (!media.ready.value) return@withLock
        try {
            val finished = finished()
            val (entries, inUse) = withContext(Dispatchers.IO) { entries(finished) }
            val stat = StatFs(media.directory.path)
            val remove = if (clear) {
                entries.filter { it.cached && it.etag !in inUse }.map { it.etag }
            } else {
                // Not what the player has loaded: it can't go from under it.
                val plan = CachePolicy.plan(
                    entries.map { if (it.etag in inUse) it.copy(cached = false) else it },
                    limit.value, stat.totalBytes, stat.availableBytes,
                )
                plan.remove
            }
            if (remove.isNotEmpty()) Log.i(Tag, "removing ${remove.size} of ${entries.size} entries")
            remove(remove)
            val after = withContext(Dispatchers.IO) { entries(finished).first }
            val stat2 = StatFs(media.directory.path)
            val plan = CachePolicy.plan(after, limit.value, stat2.totalBytes, stat2.availableBytes)
            _usage.value = CacheUsage(
                cachedBytes = after.filter { it.cached }.sumOf { it.bytes },
                downloadedBytes = plan.downloadedBytes,
                budgetBytes = plan.budgetBytes,
                lowSpace = plan.lowSpace,
                watched = finished.videos,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(Tag, "purge failed", e)
        }
    }

    /** Everything in the store, and the ETags read too recently to remove. */
    private fun entries(finished: Finished): Pair<List<CacheEntry>, Set<String>> {
        val cache = media.cache
        val downloads = media.items.value
        val inUse = inPlayer().toMutableSet()
        val entries = cache.keys.mapNotNull { key ->
            val spans = cache.getCachedSpans(key)
            if (spans.isEmpty()) return@mapNotNull null
            val bytes = spans.sumOf { it.length }
            val lastUsed = spans.maxOf { it.lastTouchTimestamp }
            val download = downloads[key]
            when {
                // still downloading: neither cached nor the viewer's yet
                download != null && download.status != CacheStatus.Cached -> {
                    inUse += key
                    CacheEntry(key, bytes, lastUsed, cached = false)
                }
                download != null -> CacheEntry(
                    key, bytes, lastUsed,
                    cached = download.info.cached,
                    finishedPodcast = download.info.podcast && key in finished.episodes,
                )
                // streamed through but not listed: a part of a track, or a whole one not added
                else -> CacheEntry(key, bytes, lastUsed, cached = true, partial = !media.isCached(key))
            }
        }
        return entries to inUse
    }

    /** Downloaded podcast episodes (by GUID) and videos (by ETag) played to their end. */
    private class Finished(val episodes: Set<String>, val videos: Set<String>)

    private suspend fun finished(): Finished {
        val downloads = media.items.value.values
        val episodes = downloads.filter { it.info.podcast }.map { it.item.etag }
        val videos = downloads.filter { it.info.videoId.isNotEmpty() && !it.info.cached }.map { it.item.etag }
        if (episodes.isEmpty() && videos.isEmpty()) return Finished(emptySet(), emptySet())
        val latest = runCatching { progress.latest() }.getOrDefault(emptyMap())
        fun done(etags: List<String>, endPercent: Int) =
            etags.filter { latest[it]?.let { o -> ProgressRules.isFinished(o, endPercent) } == true }.toSet()
        return Finished(done(episodes, ProgressRules.PodcastEndPercent), done(videos, ProgressRules.VideoEndPercent))
    }

    /** Removes [etags]: a download through the download manager (main thread), else its files. */
    private suspend fun remove(etags: List<String>) {
        if (etags.isEmpty()) return
        val listed = media.items.value
        withContext(Dispatchers.Main) {
            etags.filter { it in listed }.forEach { media.downloads.removeDownload(it) }
        }
        withContext(Dispatchers.IO) {
            etags.filter { it !in listed }.forEach { media.cache.removeResource(it) }
        }
    }

    private companion object {
        const val Tag = "CachePurge"
        const val DebounceMs = 5_000L

    }
}
