package com.takeoutfm.media

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.takeoutfm.shared.progress.TakeoutProgress

/**
 * Saves where podcast episodes got to (progress keyed by the episode's GUID, synced with the
 * server like video): every [SaveEveryMs] while playing, on pause, and when moving to another
 * item. Music tracks are left to [ListenTracker].
 */
internal class PodcastProgress(
    private val player: Player,
    private val progress: TakeoutProgress,
) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var item: MediaItem? = null
    private val tick = object : Runnable {
        override fun run() {
            save()
            handler.postDelayed(this, SaveEveryMs)
        }
    }

    fun attach() {
        player.addListener(this)
        item = player.currentMediaItem
    }

    fun detach() {
        save()
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(tick)
        if (isPlaying) handler.postDelayed(tick, SaveEveryMs) else save()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // The one that just ended (or was skipped): at its end, it's complete.
        val previous = item
        if (previous != null && previous.isPodcast && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            val duration = lastDurationMs
            if (duration > 0) progress.report(previous.mediaId, duration, duration)
        }
        item = mediaItem
    }

    private var lastDurationMs = 0L

    private fun save() {
        val current = player.currentMediaItem ?: return
        if (!current.isPodcast || current.mediaId.isEmpty()) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        lastDurationMs = duration
        progress.report(current.mediaId, player.currentPosition, duration)
    }

    private companion object {
        const val SaveEveryMs = 10_000L
    }
}
