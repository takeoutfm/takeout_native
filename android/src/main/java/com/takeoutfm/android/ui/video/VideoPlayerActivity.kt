package com.takeoutfm.android.ui.video

import androidx.media3.exoplayer.offline.DownloadService
import com.takeoutfm.shared.util.formatBytes
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
import androidx.media3.common.ForwardingPlayer
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
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.media.video.TrackPicks
import com.takeoutfm.media.video.applySubtitlePreference
import com.takeoutfm.media.video.selectBestAudioTrack
import com.takeoutfm.media.video.selectSubtitleTrack
import com.takeoutfm.android.R
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.settings.PlaySettings
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
    @Inject lateinit var mediaCache: TakeoutMediaCache
    @Inject lateinit var settings: PlaySettings

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private var session: MediaSession? = null
    private var details: VideoDetails? = null
    private var hasPlayed = false
    private var audioChosen = false
    private var saver: Job? = null

    /** The languages picked in the player's menus, kept for the next titles (the live player's too). */
    private lateinit var picks: TrackPicks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Downloaded videos play from the device (offline too); others stream.
        player = playerFactory.builder(mediaCache, video = true)
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
        picks = TrackPicks(player, settings::setAudioLanguage, settings::setSubtitleLanguage)
        player.addListener(picks)
        playerView = PlayerView(this).apply {
            // ⏮ ⏭ are the previous and next episode (VIDEO-7), shown only when there's one.
            player = episodes
            setShowSubtitleButton(true)
            // until the title's details say there's an episode that way
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            keepScreenOn = false
            setErrorMessageProvider { e -> android.util.Pair(0, getString(R.string.cant_play_reason, e.errorCodeName)) }
        }
        setContentView(playerView)
        // Media keys, Assistant and the picture-in-picture controls reach the player.
        session = MediaSession.Builder(this, episodes).setId("video").build()
        val id = intent.getStringExtra(ExtraId) ?: return finish()
        play(id, intent.getBooleanExtra(ExtraStartOver, false))
    }

    private fun play(id: String, startOver: Boolean) {
        lifecycleScope.launch {
            val d = try {
                // A series plays the episode it would start (Resume S1E2…) as that episode, so
                // the episode buttons and on-to-the-next work as for an episode opened itself.
                video.details(id).let { d -> d.playEpisodeId?.let { video.details(it) } ?: d }
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
            // only the episode buttons there's an episode for (Media3 would grey out the others)
            playerView.setShowPreviousButton(d.previousEpisodeId != null)
            playerView.setShowNextButton(d.nextEpisodeId != null)
            val resumeMs = if (startOver) 0 else runCatching { progress.resumePositionMs(d.etag) }.getOrDefault(0)
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(d.videoUri)
                    .setMediaId(d.etag)
                    // Where a download of it is kept.
                    .setCustomCacheKey(d.etag.ifEmpty { null })
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(d.title).setArtworkUri(android.net.Uri.parse(d.backdropUri)).build())
                    .build(),
                resumeMs,
            )
            // subtitles in the language picked last (here or in the live player)
            picks.auto { applySubtitlePreference(player, settings.subtitleLanguage) }
            player.prepare()
            player.play()
        }
    }

    /**
     * The player as the controls, media keys and picture-in-picture see it: previous and next go
     * to the series' previous and next episode (resumed where it was left), and are offered only
     * when there is one. A movie has neither.
     */
    @OptIn(UnstableApi::class)
    private val episodes: Player by lazy {
        object : ForwardingPlayer(player) {
            private fun previousId() = details?.previousEpisodeId
            private fun nextId() = details?.nextEpisodeId

            override fun isCommandAvailable(command: Int): Boolean = when (command) {
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> previousId() != null
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> nextId() != null
                else -> super.isCommandAvailable(command)
            }

            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                    .removeAll(
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    )
                if (previousId() != null) builder.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                if (nextId() != null) builder.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                return builder.build()
            }

            override fun hasPreviousMediaItem() = previousId() != null
            override fun hasNextMediaItem() = nextId() != null
            override fun seekToPrevious() = goTo(previousId())
            override fun seekToPreviousMediaItem() = goTo(previousId())
            override fun seekToNext() = goTo(nextId())
            override fun seekToNextMediaItem() = goTo(nextId())

            private fun goTo(id: String?) {
                id ?: return
                save()
                play(id, startOver = false)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            // The best audio, and the subtitles the shared rule picks, once the tracks are known.
            if (!audioChosen) audioChosen = picks.auto {
                selectBestAudioTrack(player, settings.audioLanguage).also { if (it) selectSubtitleTrack(player, settings.subtitleLanguage) }
            }
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
            // An episode ends: on to the next one, from its start. Anything else (a movie, a
            // series' last episode) returns to where it was played from, not a stopped video.
            val next = details?.nextEpisodeId
            when {
                next != null -> play(next, startOver = true)
                else -> offerRemoval { finish() }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()

        override fun onPlayerError(error: PlaybackException) {
            saver?.cancel()
        }
    }

    /**
     * A downloaded title watched to its end: asks whether to remove the download (never removed
     * by itself), then [then]. Not between episodes; the Downloads page lists what's watched.
     */
    private fun offerRemoval(then: () -> Unit) {
        val d = details
        val download = d?.let { mediaCache.items.value[it.etag] }
        if (d == null || download == null || download.info.cached || isInPictureInPictureMode) return then()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.remove_watched_title)
            .setMessage(getString(R.string.remove_watched, download.info.title, formatBytes(download.bytes)))
            .setPositiveButton(R.string.remove_download) { _, _ ->
                DownloadService.sendRemoveDownload(this, com.takeoutfm.android.playback.DownloadService::class.java, d.etag, false)
            }
            .setNegativeButton(R.string.keep_download, null)
            .setOnDismissListener { then() }
            .show()
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
        player.removeListener(picks)
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
