package com.google.jetstream.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.jetstream.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Plays music in the background: owns the music player and its queue, so playback continues
 * while browsing (or with the app in the background), and exposes a [MediaSession] so remote
 * keys, Assistant and Google TV's now-playing controls work anywhere. The UI talks to it
 * through [MusicPlayer]. Video playback stays screen-scoped and separate.
 */
@AndroidEntryPoint
class MusicService : MediaSessionService() {
    @Inject lateinit var playerFactory: TakeoutPlayerFactory

    private var session: MediaSession? = null

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

        // Opening the session from Google TV's now-playing card returns to the app.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
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
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
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
