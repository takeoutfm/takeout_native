package com.takeoutfm.mobile.ui.video

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.media.video.selectBestAudioTrack
import com.takeoutfm.mobile.R
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.video.TakeoutVideoRepository
import com.takeoutfm.shared.video.VideoDetails
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen video: a movie, a series (its next episode) or an episode. Media3's PlayerView
 * gives the controls, embedded subtitles and the settings menu (audio track, subtitles,
 * speed); the best audio track is picked once per title. Progress is saved every
 * [SaveEveryMs] while playing, on pause and on leaving (offline it waits in the shared
 * database). Leaving the app while playing goes picture-in-picture; at the end of an episode
 * the next one starts.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class VideoPlayerActivity : ComponentActivity() {
    @Inject lateinit var video: TakeoutVideoRepository
    @Inject lateinit var progress: TakeoutProgress
    @Inject lateinit var playerFactory: TakeoutPlayerFactory

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private var session: MediaSession? = null
    private var details: VideoDetails? = null
    private var hasPlayed = false
    private var audioChosen = false
    private var saver: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        player = playerFactory.builder()
            // Takes audio focus, so a video pauses the music (and calls pause the video).
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
            .also { playerFactory.addDebugLogging(it) }
        player.addListener(listener)
        playerView = PlayerView(this).apply {
            player = this@VideoPlayerActivity.player
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            keepScreenOn = false
            setErrorMessageProvider { e -> android.util.Pair(0, getString(R.string.cant_play_reason, e.errorCodeName)) }
        }
        setContentView(playerView)
        // Media keys, Assistant and the picture-in-picture controls reach the player.
        session = MediaSession.Builder(this, player).setId("video").build()
        val id = intent.getStringExtra(ExtraId) ?: return finish()
        play(id, intent.getBooleanExtra(ExtraStartOver, false))
    }

    private fun play(id: String, startOver: Boolean) {
        lifecycleScope.launch {
            val d = try {
                video.details(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (d == null || d.videoUri.isEmpty()) {
                android.widget.Toast.makeText(this@VideoPlayerActivity, if (d == null) R.string.cant_load else R.string.no_playable_file, android.widget.Toast.LENGTH_LONG).show()
                return@launch finish()
            }
            details = d
            hasPlayed = false
            audioChosen = false
            val resumeMs = if (startOver) 0 else runCatching { progress.resumePositionMs(d.etag) }.getOrDefault(0)
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(d.videoUri)
                    .setMediaId(d.etag)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(d.title).setArtworkUri(android.net.Uri.parse(d.backdropUri)).build())
                    .build(),
                resumeMs,
            )
            player.prepare()
            player.play()
        }
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            if (!audioChosen) audioChosen = selectBestAudioTrack(player, null)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying
            if (isPlaying) {
                hasPlayed = true
                startSaving()
            } else {
                saver?.cancel()
                save()
            }
            updatePip()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED) return
            save()
            // An episode ends: on to the next one, from its start.
            details?.nextEpisodeId?.let { play(it, startOver = true) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()

        override fun onPlayerError(error: PlaybackException) {
            saver?.cancel()
        }
    }

    private fun startSaving() {
        saver?.cancel()
        saver = lifecycleScope.launch {
            while (isActive) {
                delay(SaveEveryMs)
                save()
            }
        }
    }

    /** Saves where we are, once playback really started (a failed load never resets it). */
    private fun save() {
        val d = details ?: return
        if (!hasPlayed || player.playerError != null) return
        progress.report(d.etag, player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0)
    }

    private fun pipParams(): PictureInPictureParams {
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            // Within what Android allows (about 2.39:1 to 1:2.39).
            Rational(size.width, size.height).let { r ->
                when {
                    r.toFloat() > 2.39f -> Rational(239, 100)
                    r.toFloat() < 1 / 2.39f -> Rational(100, 239)
                    else -> r
                }
            }
        } else {
            Rational(16, 9)
        }
        return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(player.isPlaying)
        }.build()
    }

    private fun updatePip() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Before Android 12, enter picture-in-picture by hand when the user leaves while playing. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player.isPlaying) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) playerView.hideController()
    }

    override fun onStop() {
        super.onStop()
        save()
        // Closed from picture-in-picture (or backgrounded without it): stop.
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        save()
        saver?.cancel()
        session?.release()
        player.removeListener(listener)
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val ExtraId = "id"
        private const val ExtraStartOver = "startOver"
        private const val SaveEveryMs = 10_000L

        /** Plays [id] ("m-…", "s-…" or "e-…"), resuming unless [startOver]. */
        fun start(context: Context, id: String, startOver: Boolean) {
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putExtra(ExtraId, id)
                    .putExtra(ExtraStartOver, startOver)
            )
        }
    }
}
