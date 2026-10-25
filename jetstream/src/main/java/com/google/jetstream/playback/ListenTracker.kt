package com.google.jetstream.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.jetstream.data.listen.ListenReporter
import java.time.Instant

/**
 * Watches the music player and tells [ListenReporter] when a track starts and when it has been
 * listened to: actual playing time (not position, so seeking ahead doesn't count) reaching
 * [ListenReporter.countsAsListened]. Live streams aren't reported (no ETag, no fixed tracks).
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
        startTrack(mediaItem)
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
        if (player.isPlaying) playedMs += (now - lastTick).coerceAtLeast(0)
        lastTick = now
        val track = item ?: return
        if (reported || track.mediaId.isEmpty()) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (ListenReporter.countsAsListened(playedMs, duration)) {
            reported = true
            val m = track.mediaMetadata
            reporter.listened(
                etag = track.mediaId,
                artist = m.artist?.toString().orEmpty(),
                title = m.title?.toString().orEmpty(),
                album = m.albumTitle?.toString().orEmpty(),
                at = Instant.now(),
            )
        }
    }

    private companion object {
        const val TickMs = 5_000L
    }
}
