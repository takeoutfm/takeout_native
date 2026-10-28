package com.takeoutfm.shared.listen

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.db.TakeoutDb
import com.takeoutfm.shared.model.ApiActivityEvents
import com.takeoutfm.shared.model.ApiTrackEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/** A finished listen waiting to be reported. */
data class PendingListen(
    val etag: String,
    val artist: String,
    val title: String,
    val album: String,
    /** Unix seconds. */
    val listenedAt: Long,
    val toTakeout: Boolean,
    val toListenBrainz: Boolean,
)

/**
 * Reports music plays — to the Takeout server's activity and/or ListenBrainz, per the app's
 * settings ([reportPlays], [submitListens]) — once a track counts as listened
 * ([countsAsListened]). Reports that fail (offline, server down) are kept on the device (the
 * `pending_listen` table) and retried with the next one.
 */
class ListenReporter(
    private val api: TakeoutApi,
    private val listenBrainz: ListenBrainzAccount,
    private val db: TakeoutDb,
    private val reportPlays: () -> Boolean,
    private val submitListens: () -> Boolean,
) : ListenSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val toListenBrainz get() = submitListens() && listenBrainz.user.value != null

    /** A track started playing: tells ListenBrainz (its "playing now"), if enabled. */
    override fun started(artist: String, title: String, album: String) {
        if (!toListenBrainz || artist.isEmpty() || title.isEmpty()) return
        scope.launch {
            runCatching {
                listenBrainz.playingNow(ListenTrack(artist, title, album.ifEmpty { null }))
            }
        }
    }

    /** A track counted as listened; [etag] empty for streams (Takeout can't record those). */
    override fun listened(etag: String, artist: String, title: String, album: String, at: Instant) {
        val listen = PendingListen(
            etag = etag,
            artist = artist,
            title = title,
            album = album,
            listenedAt = at.epochSeconds,
            toTakeout = reportPlays() && etag.isNotEmpty(),
            toListenBrainz = toListenBrainz && artist.isNotEmpty() && title.isNotEmpty(),
        )
        if (!listen.toTakeout && !listen.toListenBrainz) return
        scope.launch { lock.withLock { flush(load() + listen) } }
    }

    /** Resends reports kept from earlier failures (e.g. when the app returns to the foreground). */
    fun retry() {
        scope.launch {
            lock.withLock {
                val pending = load()
                if (pending.isNotEmpty()) flush(pending)
            }
        }
    }

    /** Sends what it can; keeps the rest. Takeout and ListenBrainz succeed or fail separately. */
    private suspend fun flush(all: List<PendingListen>) {
        var pending = all.takeLast(MaxPending)
        val takeout = pending.filter { it.toTakeout }
        if (takeout.isNotEmpty()) {
            val sent = try {
                val events = takeout.map {
                    ApiTrackEvent(it.etag, Instant.fromEpochSeconds(it.listenedAt).toString())
                }
                api.postActivity(ApiActivityEvents(events))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (sent) pending = pending.map { it.copy(toTakeout = false) }
        }
        val lbz = pending.filter { it.toListenBrainz }
        if (lbz.isNotEmpty() && listenBrainz.user.value != null) {
            val sent = try {
                listenBrainz.submitListens(
                    lbz.map {
                        ListenTrack(it.artist, it.title, it.album.ifEmpty { null }) to it.listenedAt
                    }
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (sent) pending = pending.map { it.copy(toListenBrainz = false) }
        }
        save(pending.filter { it.toTakeout || it.toListenBrainz })
    }

    /** What's waiting to be reported, oldest first. */
    internal fun load(): List<PendingListen> =
        db.listensQueries.all().executeAsList().map {
            PendingListen(
                it.etag, it.artist, it.title, it.album, it.listened_at,
                it.to_takeout != 0L, it.to_listenbrainz != 0L,
            )
        }

    private fun save(list: List<PendingListen>) {
        db.listensQueries.transaction {
            db.listensQueries.clear()
            list.forEach { insert(db, it) }
        }
    }

    companion object {
        private const val MaxPending = 500

        fun insert(db: TakeoutDb, l: PendingListen) = db.listensQueries.insert(
            l.etag, l.artist, l.title, l.album, l.listenedAt,
            if (l.toTakeout) 1 else 0, if (l.toListenBrainz) 1 else 0,
        )
    }
}
