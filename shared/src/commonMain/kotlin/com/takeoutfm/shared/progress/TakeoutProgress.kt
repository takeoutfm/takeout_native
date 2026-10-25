package com.takeoutfm.shared.progress

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.db.DbWriter
import com.takeoutfm.shared.db.TakeoutDb
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.model.ApiOffsets
import com.takeoutfm.shared.progress.ProgressRules.confirmed
import com.takeoutfm.shared.progress.ProgressRules.keepNewest
import com.takeoutfm.shared.progress.ProgressRules.newestByEtag
import com.takeoutfm.shared.progress.ProgressRules.selectInProgress
import com.takeoutfm.shared.progress.ProgressRules.shouldResume
import com.takeoutfm.shared.video.ProgressSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * Watch progress synced with the server's /api/progress, keyed by the media file's ETag.
 *
 * Every report is saved on the device first (newest per title, in the `progress` table) and
 * marked pending until the server accepts it, so progress made offline, or whose upload failed,
 * isn't lost: pending updates are resent with the next report, on app start and when the app
 * comes back to the foreground ([sync]). Resume and Continue Watching use the newer of the
 * server's and the device's offsets (by date, so another device's later progress still wins),
 * and fall back to the device's alone when the server can't be reached. Cleared on sign-out.
 */
class TakeoutProgress(
    private val api: TakeoutApi,
    session: TakeoutSession,
    private val db: TakeoutDb,
) : ProgressSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writer = DbWriter()
    private val lock = Mutex()

    /** Newest local offset per ETag. */
    private val local: MutableStateFlow<Map<String, ApiOffset>>

    /** ETags whose local offset the server hasn't confirmed yet. */
    private val pending: MutableStateFlow<Set<String>>

    init {
        val saved = db.progressQueries.all().executeAsList()
        local = MutableStateFlow(
            saved.associate {
                it.etag to ApiOffset(it.etag, it.position.toInt(), it.duration.toInt(), it.date)
            }
        )
        pending = MutableStateFlow(saved.filter { it.pending != 0L }.map { it.etag }.toSet())
        scope.launch {
            session.state.collect { state ->
                if (!state.isLoggedIn) {
                    clear()
                } else {
                    sync()
                }
            }
        }
    }

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
        val newest = listOfNotNull(local.value[etag], remote).maxByOrNull { it.instant() } ?: return 0
        return if (shouldResume(newest.offset, newest.duration)) newest.offset * 1000L else 0
    }

    /**
     * Started-but-unfinished offsets updated within the last [days] days ([ProgressRules.AllTime]
     * for no limit), most recent first. Merges in local saves the server may not have yet.
     */
    override suspend fun inProgress(days: Int): List<ApiOffset> =
        selectInProgress(latest().values, days, Clock.System.now())

    /** The newest offset for every ETag, from the server plus local saves (local alone offline). */
    override suspend fun latest(): Map<String, ApiOffset> {
        val remote = try {
            api.progress().offsets
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return newestByEtag(remote + local.value.values)
    }

    fun report(etag: String, positionMs: Long, durationMs: Long) {
        if (etag.isEmpty() || positionMs < 0) return
        val duration = if (durationMs > 0) (durationMs / 1000).toInt() else 0
        var offset = (positionMs / 1000).toInt()
        if (duration > 0) offset = offset.coerceAtMost(duration)
        val previous = local.value[etag]
        if (previous != null && previous.offset == offset) return // nothing new to save
        val now = Clock.System.now()
        val o = ApiOffset(etag, offset, duration, now.toString())
        pending.update { it + etag }
        local.update { all ->
            // Keep the newest titles only; anything still pending is always kept.
            val updated = all + (etag to o)
            if (updated.size <= MaxLocal) {
                updated
            } else {
                keepNewest(updated.values, pending.value, MaxLocal).associateBy { it.etag }
            }
        }
        writer.write {
            db.progressQueries.transaction {
                db.progressQueries.upsert(
                    etag, offset.toLong(), duration.toLong(), o.date, now.toEpochMilliseconds(), 1,
                )
                db.progressQueries.prune(MaxLocal.toLong())
            }
        }
        scope.launch { sync() }
    }

    /** Sends every pending update in one request; what fails stays pending for next time. */
    suspend fun sync() {
        lock.withLock {
            val batch = pending.value.mapNotNull { local.value[it] }
            if (batch.isEmpty()) return
            try {
                api.updateProgress(ApiOffsets(batch))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return // offline or server error: retried on the next report, start or resume
            }
            val done = confirmed(batch, local.value)
            pending.update { it - done.toSet() }
            writer.write { db.progressQueries.confirm(done) }
        }
    }

    private fun clear() {
        local.value = emptyMap()
        pending.value = emptySet()
        writer.write { db.progressQueries.clear() }
    }

    companion object {
        const val MaxLocal = 500
    }
}
