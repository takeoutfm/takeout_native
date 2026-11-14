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
            val order = cached.sortedWith(
                compareByDescending<CacheEntry> { it.partial }
                    .thenByDescending { it.finishedPodcast }
                    .thenBy { it.lastUsedMs }
            )
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
}

private const val GB = 1024L * 1024 * 1024
