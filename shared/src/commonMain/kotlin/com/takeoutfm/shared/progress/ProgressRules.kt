package com.takeoutfm.shared.progress

import com.takeoutfm.shared.model.ApiOffset
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant

/** Rules for watch/listen progress (offsets keyed by media ETag), shared by every app. */
object ProgressRules {
    /** Offsets before this are treated as not started. */
    const val MinResumeSeconds = 10

    /** Within this much of the end counts as finished, so the title starts over. */
    const val FinishedSeconds = 120

    /** For [selectInProgress]: no age limit. */
    const val AllTime = 0

    fun shouldResume(offset: Int, duration: Int): Boolean =
        offset >= MinResumeSeconds && (duration <= 0 || offset < duration - FinishedSeconds)

    fun isFinished(o: ApiOffset): Boolean =
        o.duration > 0 && o.offset >= o.duration - FinishedSeconds

    /** The newest offset (by date) for each ETag. */
    fun newestByEtag(offsets: Collection<ApiOffset>): Map<String, ApiOffset> {
        val newest = HashMap<String, ApiOffset>()
        for (o in offsets) {
            val current = newest[o.etag]
            if (current == null || o.instant() > current.instant()) newest[o.etag] = o
        }
        return newest
    }

    /**
     * ETags of [sent] the server now has: those whose local offset is still the one sent (a
     * newer report made meanwhile stays pending).
     */
    fun confirmed(sent: List<ApiOffset>, local: Map<String, ApiOffset>): List<String> =
        sent.filter { local[it.etag] == it }.map { it.etag }

    /** At most [max] of [offsets], newest first, plus every [pending] one. */
    fun keepNewest(offsets: Collection<ApiOffset>, pending: Set<String>, max: Int): List<ApiOffset> {
        val sorted = offsets.sortedByDescending { it.instant() }
        return sorted.filterIndexed { i, o -> i < max || o.etag in pending }
    }

    /**
     * Offsets that are started but not finished and were updated within [days] days of [now]
     * ([AllTime] for no limit), most recent first.
     */
    fun selectInProgress(offsets: Collection<ApiOffset>, days: Int, now: Instant): List<ApiOffset> {
        val since = if (days == AllTime) Instant.DISTANT_PAST else now - days.days
        return offsets
            .filter { shouldResume(it.offset, it.duration) && it.instant() > since }
            .sortedByDescending { it.instant() }
    }
}

/** When this offset was saved; the distant past when the date is missing or malformed. */
fun ApiOffset.instant(): Instant =
    runCatching { Instant.parse(date) }.getOrDefault(Instant.DISTANT_PAST)
