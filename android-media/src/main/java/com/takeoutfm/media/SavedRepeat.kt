package com.takeoutfm.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.takeoutfm.shared.settings.RepeatSetting

/**
 * Keeps Repeat All on across queues and service restarts ([RepeatSetting.repeatAll]): a new
 * player starts with the saved mode, and turning repeat off or to All, from anywhere (the app,
 * Android Auto, the notification), is saved. Repeat One isn't saved; it lasts until something
 * else is played, which goes back to the saved mode.
 */
class SavedRepeat(
    private val player: Player,
    private val settings: RepeatSetting,
) : Player.Listener {
    private val saved: Int
        get() = if (settings.repeatAll.value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

    fun attach() {
        player.repeatMode = saved
        player.addListener(this)
    }

    fun detach() = player.removeListener(this)

    override fun onRepeatModeChanged(repeatMode: Int) {
        when (repeatMode) {
            Player.REPEAT_MODE_OFF -> settings.setRepeatAll(false)
            Player.REPEAT_MODE_ALL -> settings.setRepeatAll(true)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            player.repeatMode == Player.REPEAT_MODE_ONE
        ) {
            player.repeatMode = saved
        }
    }
}
