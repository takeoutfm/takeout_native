package com.takeoutfm.shared.offline

/**
 * One file (or, on Android, one track's chunks) in an app's media store, as the purge sees it.
 */
data class CacheEntry(
    /** The media's ETag, which the app removes it by. */
    val etag: String,
    val bytes: Long,
    /** When it was last played, or kept if never played since (epoch ms). */
    val lastUsedMs: Long,
    /** Added by Playback Caching: may be purged. False: downloaded by the viewer, never purged. */
    val cached: Boolean,
    /** Only part of a track (Android: chunks of one never played or cached whole). */
    val partial: Boolean = false,
    /** A podcast episode played to its end. */
    val finishedPodcast: Boolean = false,
)

/** How much Playback Caching may keep. */
sealed interface CacheLimit {
    /** [CachePolicy.AutoShare] of the volume, at most [CachePolicy.AutoMaxBytes]. */
    data object Auto : CacheLimit

    data class Fixed(val bytes: Long) : CacheLimit
}

/** What to purge, and how things stand after. */
data class CachePlan(
    /** The ETags to remove, in order. */
    val remove: List<String>,
    /** What cached entries may hold in all. */
    val budgetBytes: Long,
    /** What cached entries hold after the purge. */
    val cachedBytes: Long,
    /** What the viewer's own downloads hold (never purged). */
    val downloadedBytes: Long,
    /**
     * Free space would still be under [CachePolicy.minFreeBytes] with every cached entry gone:
     * the viewer's downloads (or other apps) fill the device, so the app warns.
     */
    val lowSpace: Boolean,
)

/** What a download of a known size needs first. */
data class RoomPlan(
    /** Cached entries to remove first, in order. */
    val remove: List<String>,
    /** With those removed there's room: it leaves [CachePolicy.minFreeBytes] free. */
    val enough: Boolean,
    /** What removing every cached entry could free (for "needs 5.2 GB, 3.1 GB can be freed"). */
    val freeableBytes: Long,
    /**
     * It fits at all with the cache emptied (if only by going under the floor): when false
     * there's nothing to ask, it can't be downloaded.
     */
    val fits: Boolean = true,
)

/**
 * The cache purge every app runs (PLAN.md §4.11): Playback Caching's entries are kept within a
 * budget, the least useful first to go; the viewer's own downloads are never removed.
 *
 * The budget is the smaller of the limit (Auto: 10% of the volume, at most 10 GB) and what
 * leaves [minFreeBytes] free. Over it, entries go until the cache is at [Slack] of it, so the
 * purge doesn't run again after every track: partial tracks first, then finished podcast
 * episodes, then the least recently used.
 */
object CachePolicy {
    const val AutoShare = 0.10
    const val AutoMaxBytes = 10L * GB
    const val MinFreeBytes = 2L * GB
    const val MinFreeShare = 0.10
    const val Slack = 0.90

    /** The sizes every app offers: Auto, then 1, 2, 5 and 10 GB. */
    val Choices: List<CacheLimit> = listOf(CacheLimit.Auto) + listOf(1L, 2L, 5L, 10L).map { CacheLimit.Fixed(it * GB) }

    /** The space always left free on a volume of [totalBytes]: 2 GB or 10% of it, the smaller. */
    fun minFreeBytes(totalBytes: Long): Long = minOf(MinFreeBytes, (totalBytes * MinFreeShare).toLong())

    /** What the cache may hold at most on a volume of [totalBytes] (before free space counts). */
    fun limitBytes(limit: CacheLimit, totalBytes: Long): Long = when (limit) {
        CacheLimit.Auto -> minOf((totalBytes * AutoShare).toLong(), AutoMaxBytes)
        is CacheLimit.Fixed -> limit.bytes
    }

    /**
     * What to remove from [entries] (everything in the app's store) on a volume of
     * [totalBytes] with [freeBytes] free now.
     */
    fun plan(entries: List<CacheEntry>, limit: CacheLimit, totalBytes: Long, freeBytes: Long): CachePlan {
        val (cached, downloaded) = entries.partition { it.cached }
        val cachedBytes = cached.sumOf { it.bytes }
        val minFree = minFreeBytes(totalBytes)
        // What the cache could grow to before free space would fall under the floor.
        val roomBytes = cachedBytes + freeBytes - minFree
        val budget = minOf(limitBytes(limit, totalBytes), roomBytes).coerceAtLeast(0)

        val remove = mutableListOf<String>()
        var kept = cachedBytes
        if (cachedBytes > budget) {
            val target = (budget * Slack).toLong()
            val order = cached.sortedWith(Order)
            for (entry in order) {
                if (kept <= target) break
                remove += entry.etag
                kept -= entry.bytes
            }
        }
        return CachePlan(
            remove = remove,
            budgetBytes = budget,
            cachedBytes = kept,
            downloadedBytes = downloaded.sumOf { it.bytes },
            lowSpace = roomBytes < 0,
        )
    }

    /**
     * Room for a download of [needBytes] (a movie, say), which should still leave
     * [minFreeBytes] free: cached entries go, in the purge's order, until it would; if even all
     * of them aren't enough, [RoomPlan.enough] is false and the app asks before going on.
     */
    fun makeRoom(entries: List<CacheEntry>, totalBytes: Long, freeBytes: Long, needBytes: Long): RoomPlan {
        val cached = entries.filter { it.cached }.sortedWith(Order)
        val freeable = cached.sumOf { it.bytes }
        val short = needBytes + minFreeBytes(totalBytes) - freeBytes
        if (short <= 0) return RoomPlan(emptyList(), enough = true, freeableBytes = freeable)
        if (freeable < short) {
            return RoomPlan(emptyList(), enough = false, freeableBytes = freeable, fits = needBytes <= freeBytes + freeable)
        }
        val remove = mutableListOf<String>()
        var freed = 0L
        for (entry in cached) {
            if (freed >= short) break
            remove += entry.etag
            freed += entry.bytes
        }
        return RoomPlan(remove, enough = true, freeableBytes = freeable)
    }

    /** Which cached entries go first: partial tracks, finished episodes, then the least recently used. */
    private val Order = compareByDescending<CacheEntry> { it.partial }
        .thenByDescending { it.finishedPodcast }
        .thenBy { it.lastUsedMs }
}

private const val GB = 1024L * 1024 * 1024
