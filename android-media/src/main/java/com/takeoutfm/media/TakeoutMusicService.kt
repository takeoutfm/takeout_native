package com.takeoutfm.media

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.progress.TakeoutProgress

/**
 * Plays music in the background: owns the music player and its queue, so playback continues
 * while browsing (or with the app in the background), and exposes a [MediaSession] so remote
 * keys, Assistant and Google TV's now-playing controls work anywhere. The UI talks to it
 * through [MusicPlayer]. Video playback stays screen-scoped and separate.
 *
 * Each app subclasses it (declared in its manifest) to supply its singletons and the screen
 * that opening the session returns to; they're used from [onCreate] on, after injection.
 */
abstract class TakeoutMusicService : MediaSessionService() {
    protected abstract val playerFactory: TakeoutPlayerFactory
    protected abstract val listenReporter: ListenReporter

    /** Where songs heard on live streams are recorded; null to not keep them. */
    protected open val streamHistory: StreamHistory? = null

    /** Where podcast episodes' positions are saved; null to not save them. */
    protected open val progress: TakeoutProgress? = null

    /** The activity the system's now-playing controls open. */
    protected abstract val sessionActivity: Class<out Activity>

    private var session: MediaSession? = null
    private var listenTracker: ListenTracker? = null
    private var podcastProgress: PodcastProgress? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = playerFactory.builder()
            // Music: pauses for calls, other apps' audio and video in this app; resumes after
            // short interruptions.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { playerFactory.addDebugLogging(it) }
        // Reports plays to Takeout / ListenBrainz per the Music settings.
        listenTracker = ListenTracker(player, listenReporter).also { it.attach() }
        player.addListener(StreamTitles(player, streamHistory))
        progress?.let { podcastProgress = PodcastProgress(player, it).also { p -> p.attach() } }

        // Opening the session from the system's now-playing controls returns to the app.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, sessionActivity)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setId("music")
            .setSessionActivity(openApp)
            .setCallback(StreamUriCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Nothing playing when the app is swiped away: stop rather than linger. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        listenTracker?.detach()
        podcastProgress?.detach()
        listenTracker = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * A live stream's "now playing" text (ICY metadata) only reaches listeners on the player
     * itself, and the item's own title outranks it in the merged metadata; so copy it into the
     * playing item's metadata extras, where [MusicPlayer] (and its controller) sees it. A
     * metadata-only replace doesn't interrupt playback.
     */
    @OptIn(UnstableApi::class)
    private class StreamTitles(
        private val player: ExoPlayer,
        private val history: StreamHistory?,
    ) : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            val title = (0 until metadata.length())
                .map { metadata[it] }
                .filterIsInstance<IcyInfo>()
                .firstNotNullOfOrNull { it.title?.trim()?.takeIf { t -> t.isNotEmpty() } }
                ?: return
            val index = player.currentMediaItemIndex
            val item = player.currentMediaItem ?: return
            if (item.mediaMetadata.extras?.getString(StreamTitleKey) == title) return
            history?.heard(
                station = item.mediaMetadata.title?.toString().orEmpty(),
                imageUri = item.mediaMetadata.artworkUri?.toString().orEmpty(),
                title = title,
            )
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                putString(StreamTitleKey, title)
            }
            player.replaceMediaItem(
                index,
                item.buildUpon()
                    .setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setExtras(extras)
                            // Shown by the system's now-playing controls.
                            .setSubtitle(title)
                            .build()
                    )
                    .build()
            )
        }
    }

    /**
     * Items from a controller arrive without their stream URI (Media3 doesn't pass
     * `localConfiguration` to the session); [MusicPlayer] puts it in the request metadata, and
     * this restores it.
     */
    private object StreamUriCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                if (item.localConfiguration == null && uri != null) {
                    item.buildUpon().setUri(uri).build()
                } else {
                    item
                }
            }.toMutableList()
        )
    }
}
