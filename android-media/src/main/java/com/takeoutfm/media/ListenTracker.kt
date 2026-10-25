package com.takeoutfm.media

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.listen.countsAsListened
import com.takeoutfm.shared.listen.countsAsStreamListen
import com.takeoutfm.shared.listen.parseStreamTitle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Watches the music player and tells [ListenReporter] when a track starts and when it has been
 * listened to: actual playing time (not position, so seeking ahead doesn't count) reaching
 * [countsAsListened].
 *
 * Live streams have no tracks, so their songs are followed by the stream's ICY title
 * ("Artist - Title"): a song is judged when the title changes, by
 * [countsAsStreamListen] — heard from its start, or for 4 minutes. The song
 * playing when you tune in (or rejoin after a pause) wasn't heard from its start. Stream
 * listens can only go to ListenBrainz (Takeout needs an ETag).
 */
class ListenTracker(
    private val player: Player,
    private val reporter: ListenReporter,
) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var item: MediaItem? = null
    private var playedMs = 0L
    private var reported = false
    private var lastTick = 0L

    // The live stream's current song (raw ICY title), how long it's been heard, and whether
    // it was heard from its start.
    private var streamSong: String? = null
    private var streamHeardMs = 0L
    private var streamFromStart = false
    private var streamStartedAt: Instant = Clock.System.now()

    private val tick = object : Runnable {
        override fun run() {
            accumulate()
            handler.postDelayed(this, TickMs)
        }
    }

    fun attach() {
        player.addListener(this)
        startTrack(player.currentMediaItem)
        if (player.isPlaying) resume()
    }

    fun detach() {
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        accumulate()
        // TakeoutMusicService replaces a stream's item to show its ICY title: same item, not a new one.
        val sameItem = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            mediaItem?.localConfiguration?.uri == item?.localConfiguration?.uri
        if (sameItem) {
            item = mediaItem
            return
        }
        endStreamSong(ended = false)
        startTrack(mediaItem)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Stopped (e.g. a live stream paused and rejoined): the song wasn't heard to its end,
        // and the next title seen won't have been heard from its start.
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            accumulate()
            endStreamSong(ended = false)
        }
    }

    override fun onMetadata(metadata: Metadata) {
        val title = (0 until metadata.length())
            .map { metadata[it] }
            .filterIsInstance<IcyInfo>()
            .firstNotNullOfOrNull { it.title?.trim()?.takeIf { t -> t.isNotEmpty() } }
            ?: return
        if (title == streamSong) return
        accumulate()
        // A change seen while listening means the new song starts now.
        val heardChange = streamSong != null
        endStreamSong(ended = true)
        streamSong = title
        streamHeardMs = 0
        streamFromStart = heardChange
        streamStartedAt = Clock.System.now()
        parseStreamTitle(title)?.let { (artist, song) ->
            reporter.started(artist, song, "")
        }
    }

    /** Judges the stream song that's ending ([ended]: the next song began) and forgets it. */
    private fun endStreamSong(ended: Boolean) {
        val song = streamSong ?: return
        streamSong = null
        val (artist, title) = parseStreamTitle(song) ?: return
        if (countsAsStreamListen(ended && streamFromStart, streamHeardMs)) {
            reporter.listened(
                etag = "", artist = artist, title = title, album = "", at = streamStartedAt,
            )
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            resume()
        } else {
            accumulate()
            handler.removeCallbacks(tick)
        }
    }

    private fun resume() {
        lastTick = System.currentTimeMillis()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TickMs)
    }

    private fun startTrack(mediaItem: MediaItem?) {
        item = mediaItem
        playedMs = 0
        reported = false
        lastTick = System.currentTimeMillis()
        val track = mediaItem?.takeIf { it.mediaId.isNotEmpty() } ?: return
        val m = track.mediaMetadata
        reporter.started(
            m.artist?.toString().orEmpty(), m.title?.toString().orEmpty(),
            m.albumTitle?.toString().orEmpty(),
        )
    }

    private fun accumulate() {
        val now = System.currentTimeMillis()
        if (player.isPlaying) {
            val delta = (now - lastTick).coerceAtLeast(0)
            playedMs += delta
            if (streamSong != null) streamHeardMs += delta
        }
        lastTick = now
        val track = item ?: return
        if (reported || track.mediaId.isEmpty()) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (countsAsListened(playedMs, duration)) {
            reported = true
            val m = track.mediaMetadata
            reporter.listened(
                etag = track.mediaId,
                artist = m.artist?.toString().orEmpty(),
                title = m.title?.toString().orEmpty(),
                album = m.albumTitle?.toString().orEmpty(),
                at = Clock.System.now(),
            )
        }
    }

    private companion object {
        const val TickMs = 5_000L
    }
}
