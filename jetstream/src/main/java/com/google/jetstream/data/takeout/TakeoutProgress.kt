package com.google.jetstream.data.takeout

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watch progress synced with the server's /api/progress, keyed by the media file's ETag.
 *
 * Reports are fire-and-forget on an app-wide scope so the final save still goes out after the
 * player screen is gone. Recent reports are also kept in memory, so reopening a title right away
 * resumes correctly even before the server has the update.
 */
@Singleton
class TakeoutProgress @Inject constructor(
    private val api: TakeoutApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val local = ConcurrentHashMap<String, ApiOffset>()

    /** Where to resume [etag], in ms; 0 to start from the beginning. */
    suspend fun resumePositionMs(etag: String): Long {
        if (etag.isEmpty()) return 0
        val remote = try {
            api.progress().offsets.firstOrNull { it.etag == etag }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val newest = listOfNotNull(local[etag], remote).maxByOrNull { it.instant() } ?: return 0
        return if (shouldResume(newest.offset, newest.duration)) newest.offset * 1000L else 0
    }

    /**
     * Started-but-unfinished offsets updated within the last [days] days ([TakeoutSettings.AllTime]
     * for no limit), most recent first. Merges in local saves the server may not have yet.
     */
    suspend fun inProgress(days: Int): List<ApiOffset> =
        selectInProgress(latest().values, days, Instant.now())

    /** The newest offset for every ETag, from the server plus local saves. */
    suspend fun latest(): Map<String, ApiOffset> {
        val newest = HashMap<String, ApiOffset>()
        for (o in api.progress().offsets + local.values) {
            val current = newest[o.etag]
            if (current == null || o.instant() > current.instant()) newest[o.etag] = o
        }
        return newest
    }

    fun report(etag: String, positionMs: Long, durationMs: Long) {
        if (etag.isEmpty() || positionMs < 0) return
        val duration = if (durationMs > 0) (durationMs / 1000).toInt() else 0
        var offset = (positionMs / 1000).toInt()
        if (duration > 0) offset = offset.coerceAtMost(duration)
        val previous = local[etag]
        if (previous != null && previous.offset == offset) return // nothing new to save
        val update = ApiOffset(etag, offset, duration, Instant.now().toString())
        local[etag] = update
        scope.launch {
            try {
                api.updateProgress(ApiOffsets(listOf(update)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort; the next report (or the local copy) covers it.
            }
        }
    }

    companion object {
        const val MinResumeSeconds = 10

        /** Within this much of the end counts as finished, so the title starts over. */
        const val FinishedSeconds = 120

        fun shouldResume(offset: Int, duration: Int): Boolean =
            offset >= MinResumeSeconds && (duration <= 0 || offset < duration - FinishedSeconds)

        fun isFinished(o: ApiOffset): Boolean =
            o.duration > 0 && o.offset >= o.duration - FinishedSeconds

        /**
         * Offsets that are started but not finished and were updated within [days] days of [now]
         * ([TakeoutSettings.AllTime] for no limit), most recent first.
         */
        fun selectInProgress(offsets: Collection<ApiOffset>, days: Int, now: Instant): List<ApiOffset> {
            val since = if (days == TakeoutSettings.AllTime) {
                Instant.EPOCH
            } else {
                now.minus(Duration.ofDays(days.toLong()))
            }
            return offsets
                .filter { shouldResume(it.offset, it.duration) && it.instant().isAfter(since) }
                .sortedByDescending { it.instant() }
        }

        fun ApiOffset.instant(): Instant =
            runCatching { Instant.parse(date) }.getOrDefault(Instant.EPOCH)
    }
}
