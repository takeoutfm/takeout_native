package com.takeoutfm.tv.data.listen

import android.content.Context
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.model.ApiActivityEvents
import com.takeoutfm.shared.model.ApiTrackEvent
import com.takeoutfm.tv.data.takeout.TakeoutSettings
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
 * settings — once a track counts as listened ([com.takeoutfm.shared.listen.countsAsListened]). Reports that fail (offline,
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
    }
}
