package com.takeoutfm.media

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.takeoutfm.shared.music.MusicHistory

/**
 * Records where the playing queue got to in its Recently Played entry (the id in each item's
 * extras, [HistoryKey]), so replaying it resumes there: every [SaveEveryMs] while playing, on
 * pause and on moving to another item. It runs in the service, so it works however the queue
 * was started (the app, Android Auto) and with no screen open.
 */
internal class HistoryProgress(
    private val player: Player,
    private val history: MusicHistory,
) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            save()
            handler.postDelayed(this, SaveEveryMs)
        }
    }

    fun attach() = player.addListener(this)

    fun detach() {
        save()
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(tick)
        if (isPlaying) handler.postDelayed(tick, SaveEveryMs) else save()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = save()

    private fun save() {
        val id = player.currentMediaItem?.historyId ?: return
        history.progress(id, player.currentMediaItemIndex, player.currentPosition)
    }

    private companion object {
        const val SaveEveryMs = 10_000L
    }
}
