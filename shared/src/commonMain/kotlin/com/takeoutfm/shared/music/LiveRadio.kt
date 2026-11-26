package com.takeoutfm.shared.music

import kotlin.math.max
import kotlinx.serialization.Serializable

// Live radio: a live station is a schedule of library tracks, and tuning in plays the track on
// now from `now - start`, like real radio. The server sends the schedule and its time; the apps
// work out what's on and follow it. The rules here are the server's RADIO.md ("Apps").

/** A regular show on a live station ("Gary Numan Fridays"). */
@Serializable
data class RadioShow(
    val name: String,
    val imageUri: String = "",
    val description: String = "",
)

/** A track scheduled on a live station; times are the server's, in epoch ms. */
@Serializable
data class RadioTrack(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val artist: String,
    val album: String,
    val imageUri: String,
    /** The full stream URL. */
    val uri: String,
    val etag: String,
    /** The track ("t-<id>"), for its album and ♥. */
    val trackId: String,
    /** The show it's part of, or "". */
    val show: String = "",
)

/** A live station's tracks in a range of time, in start order. */
@Serializable
data class RadioSchedule(
    val stationId: String,
    val name: String,
    val imageUri: String,
    val tracks: List<RadioTrack>,
    /** The shows [tracks] name. */
    val shows: List<RadioShow> = emptyList(),
    /** The server's clock minus the device's when this was fetched. */
    val skewMs: Long = 0,
) {
    fun show(name: String): RadioShow? = if (name.isEmpty()) null else shows.firstOrNull { it.name == name }

    /** What's on at server time [timeMs], or null when nothing is scheduled then. */
    fun onNow(timeMs: Long): OnNow? {
        val i = onNowIndex(tracks, timeMs)
        if (i < 0) return null
        val track = tracks[i]
        return OnNow(
            stationId = stationId,
            track = track,
            offsetMs = timeMs - track.startMs,
            next = tracks.getOrNull(i + 1),
            show = show(track.show),
        )
    }

    /** [track] as a queue item that the player follows live. */
    fun queueItem(track: RadioTrack): QueueItem {
        val show = show(track.show)
        return QueueItem(
            title = track.title,
            artist = track.artist,
            album = track.album,
            // The track's cover, else the show's or the station's.
            imageUri = track.imageUri.ifEmpty { show?.imageUri.orEmpty() }.ifEmpty { imageUri },
            uri = track.uri,
            etag = track.etag,
            live = LiveSlot(
                stationId = stationId,
                stationName = name,
                show = track.show,
                startMs = track.startMs,
                endMs = track.endMs,
                skewMs = skewMs,
            ),
        )
    }

    /**
     * Tuning in at server time [timeMs]: the track on now, started [MusicQueue.startPositionMs]
     * in, then the rest; null when nothing is on.
     */
    fun toQueue(timeMs: Long): MusicQueue? {
        val i = onNowIndex(tracks, timeMs)
        if (i < 0) return null
        return MusicQueue(
            title = name,
            creator = "",
            imageUri = imageUri,
            items = tracks.drop(i).map(::queueItem),
            startIndex = 0,
            startPositionMs = (timeMs - tracks[i].startMs).coerceAtLeast(0),
            location = "/api/radio/${stationId.removePrefix(TakeoutMusicRepository.StationPrefix)}",
        )
    }
}

/** What's on a live station now: the track, how far in it is, what's next and the show. */
@Serializable
data class OnNow(
    val stationId: String,
    val track: RadioTrack,
    val offsetMs: Long,
    val next: RadioTrack?,
    val show: RadioShow?,
) {
    /** "Artist – Title". */
    val line: String get() = if (track.artist.isEmpty()) track.title else "${track.artist} – ${track.title}"
}

/** What's on every live station, from one schedule request. */
@Serializable
data class RadioNow(
    /** By station id ("st-<id>"). */
    val stations: Map<String, OnNow>,
    /** When the first of those tracks ends, by the device's clock (epoch ms); 0 if none. */
    val refreshAtMs: Long,
)

/** The index of the track in [tracks] on at [timeMs] (start ≤ time < end), or -1. */
fun onNowIndex(tracks: List<RadioTrack>, timeMs: Long): Int =
    tracks.indexOfFirst { it.startMs <= timeMs && timeMs < it.endMs }

/** What the player does as a live track starts playing; see [LiveRadio.check]. */
sealed interface LiveStart {
    /** Play on as it is. */
    data object AsIs : LiveStart

    /** Off the schedule: seek to [positionMs], where the station is now. */
    data class SeekTo(val positionMs: Long) : LiveStart

    /** Too far off (or the queue is stale): fetch the schedule and tune in again. */
    data object JumpToLive : LiveStart
}

/** The live player's rules, for every app's player. */
object LiveRadio {
    /** Playback this far off the schedule is corrected (a track's length is whole seconds). */
    const val DriftMs = 5_000L

    /** A pause this short (or within the paused track) resumes behind live; longer jumps to live. */
    const val MaxBehindMs = 5 * 60_000L

    /** Behind live by more than this, the schedule has gone (the server keeps an hour). */
    const val StaleMs = 45 * 60_000L

    /** Fetch more when this few tracks are left after the playing one. */
    const val RefillAt = 2

    /** The server's time now, by the device's clock [deviceMs]. */
    fun now(slot: LiveSlot, deviceMs: Long): Long = deviceMs + slot.skewMs

    /** How far playback at [positionMs] into [slot] is behind the station at [deviceMs]. */
    fun behindMs(slot: LiveSlot, positionMs: Long, deviceMs: Long): Long =
        now(slot, deviceMs) - (slot.startMs + positionMs)

    /**
     * As [slot] plays at [positionMs] (0 for a track that just started): live, a track that's off
     * the schedule by more than [DriftMs] seeks to where the station is now, or jumps to live when
     * that's past its end (the queue is stale). [behindLive] (resumed after a pause) plays on
     * as it is, unless the schedule has gone.
     */
    fun check(slot: LiveSlot, positionMs: Long, deviceMs: Long, behindLive: Boolean): LiveStart {
        val behind = behindMs(slot, positionMs, deviceMs)
        if (behindLive) return if (behind > StaleMs) LiveStart.JumpToLive else LiveStart.AsIs
        if (behind <= DriftMs) return LiveStart.AsIs
        val at = positionMs + behind
        return if (at < slot.lengthMs - DriftMs) LiveStart.SeekTo(at) else LiveStart.JumpToLive
    }

    /**
     * Resuming [slot] at [positionMs] after a pause: true to play on behind live (a delayed copy
     * of the station), false to jump to live. Behind by less than [MaxBehindMs], or than the
     * paused track had left, plays on. [behindMs] near 0 is live anyway.
     */
    fun keepBehind(slot: LiveSlot, positionMs: Long, behindMs: Long): Boolean =
        behindMs <= max(MaxBehindMs, slot.lengthMs - positionMs)

    /** True when [left] tracks after the playing one is few enough to fetch more. */
    fun needsMore(left: Int): Boolean = left <= RefillAt

    /** The [fetched] items that come after [queued]'s last one on the same station. */
    fun after(queued: List<QueueItem>, fetched: List<QueueItem>): List<QueueItem> {
        val last = queued.lastOrNull { it.live != null }?.live ?: return fetched
        return fetched.filter { val s = it.live; s != null && s.stationId == last.stationId && s.startMs > last.startMs }
    }
}
