package com.takeoutfm.android.ui.live

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.takeoutfm.android.R
import com.takeoutfm.android.ui.theme.TakeoutTheme
import com.takeoutfm.media.TakeoutPlayerFactory
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.media.video.TrackPicker
import com.takeoutfm.media.video.applySubtitlePreference
import com.takeoutfm.media.video.audioTrackOptions
import com.takeoutfm.media.video.selectBestAudioTrack
import com.takeoutfm.media.video.subtitleTrackOptions
import com.takeoutfm.shared.guide.Guide
import com.takeoutfm.shared.guide.GuideProgram
import com.takeoutfm.shared.guide.LiveGuide
import com.takeoutfm.shared.guide.ProgramKind
import com.takeoutfm.shared.guide.TakeoutGuideRepository
import com.takeoutfm.shared.guide.Tune
import com.takeoutfm.shared.guide.displayTitle
import com.takeoutfm.shared.guide.timeLeft
import com.takeoutfm.shared.guide.tune
import com.takeoutfm.shared.guide.volume
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.Instant

/** What the screen shows over (or instead of) the video. */
private sealed interface Tuned {
    data object Tuning : Tuned
    data class Playing(val program: GuideProgram, val next: GuideProgram?) : Tuned

    /** A break (with [track] playing, if it has music), or [program] off air. */
    data class Slate(val program: GuideProgram?, val next: GuideProgram?, val track: GuideProgram? = null) : Tuned
    data class Failed(val message: String) : Tuned
}

/**
 * Live TV on a phone or tablet: plays whatever is on a channel now from where it is now, by the
 * server's clock, as the TV app does. The arrows change channel; pausing puts you behind live
 * until the program ends or you jump back. Tapping shows or hides the banner. Watch progress
 * isn't written, so flipping past things doesn't fill Continue Watching.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class LivePlayerActivity : ComponentActivity() {
    @Inject lateinit var repository: TakeoutGuideRepository
    @Inject lateinit var playerFactory: TakeoutPlayerFactory
    @Inject lateinit var mediaCache: TakeoutMediaCache

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var liveGuide: LiveGuide

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        liveGuide = LiveGuide(repository)
        player = playerFactory.builder(mediaCache, video = true)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { playerFactory.addDebugLogging(it) }
        // Close to the second is fine; starting at the nearest keyframe tunes faster.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        // Media keys, Assistant and the picture-in-picture controls reach the player.
        session = MediaSession.Builder(this, player).setId("live").build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
            override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()
        })
        val first = savedInstanceState?.getString(ExtraChannel) ?: intent.getStringExtra(ExtraChannel) ?: return finish()
        channel = first
        setContent {
            TakeoutTheme {
                LivePlayer(
                    player = player,
                    channel = channel,
                    guide = { liveGuide.get() },
                    onBack = { finish() },
                )
            }
        }
    }

    private var channel by mutableStateOf("")

    /** In picture-in-picture: just the picture, no banner or buttons. */
    private var inPip by mutableStateOf(false)

    /** The languages last chosen in the pickers, for the programs after (null: no choice yet). */
    private var audioLanguage: String? = null
    private var subtitleLanguage: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ExtraChannel, channel)
    }

    /** Another channel tuned from the guide while this one is open (or in picture-in-picture). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(ExtraChannel)?.let { channel = it }
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
            // Leaving the app while a channel plays goes picture-in-picture; break music and
            // the slate don't need the screen.
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
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // Closed from picture-in-picture, or backgrounded without it: stop.
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        session?.release()
        player.release()
        super.onDestroy()
    }

    @Composable
    private fun LivePlayer(
        player: ExoPlayer,
        channel: String,
        guide: suspend () -> Guide,
        onBack: () -> Unit,
    ) {
        var tuned by remember { mutableStateOf<Tuned>(Tuned.Tuning) }
        var current by remember { mutableStateOf<Guide?>(null) }
        var behind by remember { mutableStateOf(false) }
        var paused by remember { mutableStateOf(false) }
        var buffering by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var ended by remember { mutableStateOf(false) }
        // Bumped to show the controls (after tuning, or pausing).
        var bannerKey by remember { mutableIntStateOf(0) }
        var liveKey by remember { mutableIntStateOf(0) }
        // The video player's controls (Media3's), and whether they're showing.
        var playerView by remember { mutableStateOf<PlayerView?>(null) }
        var controlsVisible by remember { mutableStateOf(true) }
        var now by remember { mutableStateOf<Instant?>(null) }
        val cantLoad = stringResource(R.string.live_cant_load)
        val notOn = stringResource(R.string.live_not_on, channel)

        LaunchedEffect(bannerKey) { playerView?.showController() }

        DisposableEffect(player) {
            var audioChosen = false
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    audioChosen = false
                    // the subtitles chosen here follow to the next program, by language
                    applySubtitlePreference(player, subtitleLanguage)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (!audioChosen) audioChosen = selectBestAudioTrack(player, audioLanguage)
                }

                // What's picked in the controls' menus, kept by language for the programs after.
                override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                    var text = false
                    for (o in parameters.overrides.values) {
                        val language = o.mediaTrackGroup.getFormat(0).language ?: continue
                        when (o.type) {
                            C.TRACK_TYPE_AUDIO -> audioLanguage = language
                            C.TRACK_TYPE_TEXT -> {
                                subtitleLanguage = language
                                text = true
                            }
                        }
                    }
                    // Off: text disabled, or only forced subtitles allowed
                    if (!text && (C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes ||
                            parameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT != 0)
                    ) {
                        subtitleLanguage = ""
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    paused = !playWhenReady
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                        behind = true
                        bannerKey++
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    window.decorView.keepScreenOn = isPlaying
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = getString(R.string.cant_play_reason, e.errorCodeName)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    buffering = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_ENDED) ended = true
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }

        // Tunes [channel] and follows its schedule, as the TV's live player does.
        LaunchedEffect(channel, liveKey) {
            behind = false
            tuned = Tuned.Tuning
            bannerKey++
            while (isActive) {
                val g = try {
                    guide()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tuned = Tuned.Failed(cantLoad)
                    delay(RetryMs)
                    continue
                }
                current = g
                val ch = g.channel(channel)
                if (ch == null) {
                    player.stop()
                    tuned = Tuned.Failed(notOn)
                    delay(RetryMs)
                    continue
                }
                when (val t = tune(ch, g.now())) {
                    is Tune.Program -> {
                        val program = t.program
                        tuned = Tuned.Playing(program, t.next)
                        error = null
                        ended = false
                        buffering = true
                        player.volume = 1f
                        player.setMediaItem(program.toMediaItem(), t.offset.inWholeMilliseconds)
                        player.prepare()
                        player.playWhenReady = true
                        while (!ended && (behind || g.now() < program.end)) delay(TickMs)
                        if (behind) {
                            behind = false
                            bannerKey++
                            continue
                        }
                        player.stop()
                        if (g.now() < program.end) {
                            tuned = Tuned.Slate(null, t.next)
                            delay((program.end - g.now()).coerceIn(1.seconds, MaxWait))
                        }
                    }
                    is Tune.Music -> {
                        tuned = Tuned.Slate(null, t.next, t.track)
                        error = null
                        ended = false
                        player.volume = 1f
                        player.setMediaItem(t.track.toMediaItem(), t.offset.inWholeMilliseconds)
                        player.prepare()
                        player.playWhenReady = true
                        while (!ended && g.now() < t.track.end) {
                            player.volume = t.volume(g.now())
                            delay(FadeTickMs)
                        }
                        player.stop()
                        player.volume = 1f
                        if (g.now() < t.track.end) delay((t.track.end - g.now()).coerceIn(Duration.ZERO, MaxWait))
                    }
                    is Tune.Slate -> {
                        player.stop()
                        tuned = Tuned.Slate(t.program, t.next)
                        bannerKey++
                        val wait = t.until?.let { it - g.now() } ?: MaxWait
                        delay(wait.coerceIn(1.seconds, MaxWait))
                    }
                }
            }
        }

        // The banner's times and the slate's countdown.
        LaunchedEffect(current) {
            val g = current ?: return@LaunchedEffect
            while (true) {
                now = g.now()
                delay(1_000)
            }
        }

        // Live can't seek or change speed: without those commands Media3's controls leave out
        // the seek buttons and speed, and the time bar can't be dragged.
        val controlled = remember(player) { LiveControlsPlayer(player) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val playing = tuned is Tuned.Playing
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = controlled
                        // the same controls as the video player: play/pause in the middle, the
                        // time bar along the bottom, subtitles and the settings gear at its right
                        setShowSubtitleButton(true)
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowFastForwardButton(false)
                        setShowRewindButton(false)
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE }
                        )
                        setErrorMessageProvider { e -> android.util.Pair(0, getString(R.string.cant_play_reason, e.errorCodeName)) }
                        playerView = this
                    }
                },
                update = { view ->
                    // a break or a slate has nothing to control; nor the small window
                    view.useController = playing && !inPip
                },
                modifier = Modifier.fillMaxSize(),
            )

            val time = now
            when (val t = tuned) {
                is Tuned.Slate -> if (inPip) {
                    PipSlate(t, Modifier.align(Alignment.Center))
                } else {
                    SlateCard(t, time, Modifier.align(Alignment.Center))
                }
                is Tuned.Failed -> Message(t.message, Modifier.align(Alignment.Center))
                is Tuned.Playing -> {
                    if (error == null && buffering) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    }
                }
                Tuned.Tuning -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }

            // What's on, at the top while the controls show (always in a break).
            AnimatedVisibility(
                visible = !inPip && (controlsVisible || !playing),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                LiveTitle(
                    channel = channel,
                    tuned = tuned,
                    now = time,
                    paused = paused,
                    behind = behind,
                    onLive = { liveKey++ },
                )
            }
        }
    }

    companion object {
        private const val ExtraChannel = "channel"
        private const val BannerMs = 5_000L
        private const val TickMs = 500L
        private const val FadeTickMs = 100L
        private const val RetryMs = 30_000L
        private val MaxWait = 5.minutes

        /** Opens the live player on [channel]. */
        fun start(context: Context, channel: String) {
            context.startActivity(Intent(context, LivePlayerActivity::class.java).putExtra(ExtraChannel, channel))
        }
    }
}

/**
 * The channel and what's on, over the top of the picture while the controls show: the title,
 * when it's on and how long is left, Behind live and Jump to live after a pause, and what's next.
 */
@Composable
private fun LiveTitle(
    channel: String,
    tuned: Tuned,
    now: Instant?,
    paused: Boolean,
    behind: Boolean,
    onLive: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val program = (tuned as? Tuned.Playing)?.program ?: (tuned as? Tuned.Slate)?.program
            val next = (tuned as? Tuned.Playing)?.next ?: (tuned as? Tuned.Slate)?.next
            Text(
                listOfNotNull(channel, program?.block?.takeIf { it.isNotEmpty() }).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (program != null) {
                Text(
                    program.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val left = now?.takeIf { it < program.end && program.kind != ProgramKind.OffAir }?.let { timeLeft(program.end - it) }
                Text(
                    listOfNotNull(
                        program.subtitle.takeIf { program.kind == ProgramKind.Episode && it.isNotEmpty() },
                        "${program.start.localTime()} – ${program.end.localTime()}",
                        left,
                        stringResource(R.string.live_behind).takeIf { behind || paused },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (next != null && program != null) {
                Text(
                    stringResource(R.string.live_next, next.start.localTime(), next.displayTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (behind && tuned is Tuned.Playing) {
            FilledTonalButton(onClick = onLive) { Text(stringResource(R.string.live_jump)) }
        }
    }
}

/** The live player as Media3's controls see it: no seeking, no speed. */
@OptIn(UnstableApi::class)
private class LiveControlsPlayer(player: Player) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().removeAll(*Blocked).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command !in Blocked && super.isCommandAvailable(command)

    private companion object {
        val Blocked = intArrayOf(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
        )
    }
}

/** The slate in the small picture-in-picture window: what's next. */
@Composable
private fun PipSlate(slate: Tuned.Slate, modifier: Modifier) {
    val next = slate.next ?: return
    Column(modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.live_up_next), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Text(
            next.displayTitle,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A break or a program off air: what's next and when, and the break's music if any. */
@Composable
private fun SlateCard(slate: Tuned.Slate, now: Instant?, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = 420.dp)
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val track = slate.track
        if (track != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (track.imageUri.isNotEmpty()) {
                    AsyncImage(
                        model = track.imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (slate.program != null) {
            Text(stringResource(R.string.live_off_air), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        val next = slate.next
        if (next != null) {
            Text(stringResource(R.string.live_up_next), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            Text(next.displayTitle, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (now != null && next.start > now) {
                Text(
                    stringResource(R.string.live_starts_in, countdown(next.start - now)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        } else {
            Text(stringResource(R.string.live_nothing_ahead), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier
            .widthIn(max = 420.dp)
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(24.dp),
    )
}

/** "7:32", or "1:07:32" an hour or more out. */
private fun countdown(left: Duration): String {
    val s = left.inWholeSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun GuideProgram.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(videoUri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).build())
    .build()
