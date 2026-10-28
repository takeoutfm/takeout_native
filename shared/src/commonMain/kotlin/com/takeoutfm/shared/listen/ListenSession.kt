package com.takeoutfm.shared.listen

import com.takeoutfm.shared.music.QueueItem
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Where a [ListenSession] sends what it decides; [ListenReporter] in the apps. */
interface ListenSink {
    /** A track started playing (ListenBrainz's "playing now"). */
    fun started(artist: String, title: String, album: String)

    /** A track counted as listened; [etag] empty for stream songs. */
    fun listened(etag: String, artist: String, title: String, album: String, at: Instant)
}

/**
 * Decides when what's playing counts as a listen, for players that report events rather than
 * expose a Media3 player (the Mac's AVPlayer; Android's `ListenTracker` follows the same rules):
 * actual playing time, not position (seeking ahead doesn't count), reaching [countsAsListened].
 *
 * Live streams have no tracks, so their songs are followed by the stream's ICY title
 * ("Artist - Title") and judged when it changes by [countsAsStreamListen]: heard from its
 * start, or for 4 minutes. The song playing when you tune in (or rejoin after the stream
 * stopped) wasn't heard from its start. Podcast episodes aren't listens.
 *
 * Call [track] when the item changes, [playing] when playback starts or stops, [tick]
 * regularly (it counts the time since the last call while playing), [streamTitle] for each
 * ICY title and [stopped] when a stream stops.
 */
class ListenSession(
    private val sink: ListenSink,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private var item: QueueItem? = null
    private var isPlaying = false
    private var playedMs = 0L
    private var reported = false
    private var lastTick = now()

    private var streamSong: String? = null
    private var streamHeardMs = 0L
    private var streamFromStart = false
    private var streamStartedAt = now()

    /** [item] started (null: nothing is playing). */
    fun track(item: QueueItem?) {
        accumulate(durationMs = 0)
        endStreamSong(ended = false)
        this.item = item
        playedMs = 0
        reported = false
        lastTick = now()
        val track = item?.takeIf { it.isTrack } ?: return
        sink.started(track.artist, track.title, track.album)
    }

    fun playing(isPlaying: Boolean, durationMs: Long) {
        accumulate(durationMs)
        this.isPlaying = isPlaying
        lastTick = now()
    }

    /** Counts the time played since the last call; [durationMs] is 0 when unknown. */
    fun tick(durationMs: Long) = accumulate(durationMs)

    /** The stream's ICY title, as often as the player reports it. */
    fun streamTitle(icy: String) {
        val title = icy.trim().takeIf { it.isNotEmpty() } ?: return
        if (title == streamSong) return
        accumulate(durationMs = 0)
        // A change seen while listening means the new song starts now.
        val heardChange = streamSong != null
        endStreamSong(ended = true)
        streamSong = title
        streamHeardMs = 0
        streamFromStart = heardChange
        streamStartedAt = now()
        parseStreamTitle(title)?.let { (artist, song) -> sink.started(artist, song, "") }
    }

    /** The stream stopped: its song wasn't heard to its end, and the next won't be from its start. */
    fun stopped() {
        accumulate(durationMs = 0)
        endStreamSong(ended = false)
    }

    private fun endStreamSong(ended: Boolean) {
        val song = streamSong ?: return
        streamSong = null
        val (artist, title) = parseStreamTitle(song) ?: return
        if (countsAsStreamListen(ended && streamFromStart, streamHeardMs)) {
            sink.listened(etag = "", artist = artist, title = title, album = "", at = streamStartedAt)
        }
    }

    private fun accumulate(durationMs: Long) {
        val t = now()
        if (isPlaying) {
            val delta = (t - lastTick).inWholeMilliseconds.coerceAtLeast(0)
            playedMs += delta
            if (streamSong != null) streamHeardMs += delta
        }
        lastTick = t
        val track = item?.takeIf { it.isTrack } ?: return
        if (reported) return
        if (countsAsListened(playedMs, durationMs)) {
            reported = true
            sink.listened(track.etag, track.artist, track.title, track.album, at = t)
        }
    }

    // Streams have no ETag; podcast episodes save progress instead.
    private val QueueItem.isTrack get() = etag.isNotEmpty() && !podcast
}
