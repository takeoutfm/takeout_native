package com.google.jetstream.data.listen

import android.content.Context
import com.google.jetstream.data.takeout.ApiActivityEvents
import com.google.jetstream.data.takeout.ApiTrackEvent
import com.google.jetstream.data.takeout.TakeoutApi
import com.google.jetstream.data.takeout.TakeoutSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A finished listen waiting to be reported. */
@Serializable
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
 * Reports music plays — to the Takeout server's activity and/or ListenBrainz, per the Music
 * settings — once a track counts as listened ([countsAsListened]). Reports that fail (offline,
 * server down) are kept on the device and retried with the next one.
 */
@Singleton
class ListenReporter @Inject constructor(
    @ApplicationContext context: Context,
    private val api: TakeoutApi,
    private val listenBrainz: ListenBrainzAccount,
    private val settings: TakeoutSettings,
) {
    private val prefs = context.getSharedPreferences("listen_reporter", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingListen.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val toTakeout get() = settings.reportPlays.value
    private val toListenBrainz
        get() = settings.submitListens.value && listenBrainz.user.value != null

    /** A track started playing: tells ListenBrainz (its "playing now"), if enabled. */
    fun started(artist: String, title: String, album: String) {
        if (!toListenBrainz || artist.isEmpty() || title.isEmpty()) return
        scope.launch {
            runCatching {
                listenBrainz.playingNow(ListenTrack(artist, title, album.ifEmpty { null }))
            }
        }
    }

    /** A track counted as listened; [etag] empty for streams (Takeout can't record those). */
    fun listened(etag: String, artist: String, title: String, album: String, at: Instant) {
        val listen = PendingListen(
            etag = etag,
            artist = artist,
            title = title,
            album = album,
            listenedAt = at.epochSecond,
            toTakeout = toTakeout && etag.isNotEmpty(),
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
                    ApiTrackEvent(it.etag, Instant.ofEpochSecond(it.listenedAt).toString())
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

    private fun load(): List<PendingListen> =
        prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    private fun save(list: List<PendingListen>) {
        prefs.edit().putString(KEY, json.encodeToString(serializer, list)).apply()
    }

    companion object {
        private const val KEY = "pending"
        private const val MaxPending = 500

        private const val FourMinutesMs = 4 * 60_000L

        /** ListenBrainz ignores anything shorter. */
        private const val MinListenMs = 30_000L

        /** The Takeout apps' rule: 4 minutes in or half way through, whichever comes first. */
        fun countsAsListened(playedMs: Long, durationMs: Long): Boolean =
            playedMs >= FourMinutesMs || (durationMs > 0 && playedMs >= durationMs / 2)

        /**
         * A song on a live stream, judged when the stream's title changes (its length is
         * unknown): heard from its start (at least 30 s), or for 4 minutes. The song playing
         * when you tune in wasn't heard from its start.
         */
        fun countsAsStreamListen(heardFromStart: Boolean, heardMs: Long): Boolean =
            heardMs >= FourMinutesMs || (heardFromStart && heardMs >= MinListenMs)

        /**
         * A stream's ICY title as artist and title: "Artist - Title" (split at the first " - ");
         * null when it isn't in that form (station IDs, ads, show names).
         */
        fun parseStreamTitle(icy: String): Pair<String, String>? {
            val i = icy.indexOf(" - ")
            if (i <= 0) return null
            val artist = icy.substring(0, i).trim()
            val title = icy.substring(i + 3).trim()
            return if (artist.isEmpty() || title.isEmpty()) null else artist to title
        }
    }
}
