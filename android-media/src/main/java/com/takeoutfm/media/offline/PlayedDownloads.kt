package com.takeoutfm.media.offline

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import com.takeoutfm.media.historyId
import com.takeoutfm.media.toQueueItem
import com.takeoutfm.shared.music.MusicHistory

/**
 * Playback Caching's Downloads: playing writes what streams into [cache] (see
 * [TakeoutMediaCache.playbackDataSourceFactory]); once a track is wholly there, this adds it as
 * a download — completed at once from the cache, with nothing fetched — so it shows on the
 * Downloads page with the album, playlist or podcast it played in. Nothing is fetched ahead:
 * a track only partly played keeps its cached chunks for playback but isn't listed.
 * Checked when a track ends or is left, and when playback stops.
 */
@OptIn(UnstableApi::class)
internal class PlayedDownloads(
    private val context: Context,
    private val player: Player,
    private val cache: TakeoutMediaCache,
    private val history: MusicHistory,
    private val service: Class<out DownloadService>,
) : Player.Listener {
    private var item: MediaItem? = null

    fun attach() {
        player.addListener(this)
        item = player.currentMediaItem
    }

    fun detach() = player.removeListener(this)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        item?.let(::keepIfCached)
        item = mediaItem
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            item?.let(::keepIfCached)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) item?.let(::keepIfCached)
    }

    private fun keepIfCached(media: MediaItem) {
        val etag = media.mediaId
        if (!cache.isWriting || etag.isEmpty() || etag in cache.items.value || !cache.isCached(etag)) return
        val played = media.historyId?.let(history::find)
        val group = played?.let { it.location.ifEmpty { it.title } }.orEmpty()
        val request = cache.requests(listOf(media.toQueueItem()), group, played?.title.orEmpty())
            .firstOrNull() ?: return
        try {
            DownloadService.sendAddDownload(context, service, request, false)
        } catch (e: RuntimeException) {
            // Not allowed to start the service now (e.g. in the background): it stays cached
            // for playback, just not listed.
            Log.w("PlayedDownloads", "couldn't add $etag", e)
        }
    }
}
