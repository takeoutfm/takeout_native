package com.takeoutfm.tv.presentation.screens.live

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import com.takeoutfm.media.video.TrackPicker
import com.takeoutfm.media.video.audioTrackOptions
import com.takeoutfm.media.video.subtitleTrackOptions
import com.takeoutfm.shared.video.normalizeLanguage
import com.takeoutfm.tv.R
import com.takeoutfm.tv.data.util.StringConstants
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.TrackPickerDialog
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerControlsIcon
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerControllerText
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerMainFrame
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerMediaTitle
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerMediaTitleType
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerOverlay
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.rememberVideoPlayerState
import com.takeoutfm.tv.presentation.utils.handleDPadKeyEvents
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.tv.material3.Surface
import androidx.tv.material3.Icon
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.takeoutfm.media.video.applySubtitlePreference
import com.takeoutfm.media.video.selectBestAudioTrack
import com.takeoutfm.shared.guide.Guide
import com.takeoutfm.shared.guide.LiveGuide
import com.takeoutfm.shared.guide.displayTitle
import com.takeoutfm.shared.guide.timeLeft
import com.takeoutfm.shared.guide.GuideProgram
import com.takeoutfm.shared.guide.ProgramKind
import com.takeoutfm.shared.guide.TakeoutGuideRepository
import com.takeoutfm.shared.guide.Tune
import com.takeoutfm.shared.guide.tune
import com.takeoutfm.shared.guide.volume
import com.takeoutfm.tv.data.takeout.TakeoutSettings
import com.takeoutfm.tv.presentation.common.DelayedLoading
import com.takeoutfm.tv.presentation.common.Spinner
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.podcasts.ProgressBar
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.PlayerSubtitles
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.rememberPlayer
import com.takeoutfm.tv.presentation.screens.videoPlayer.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.datetime.Instant

object LivePlayerScreen {
    const val ChannelBundleKey = "channel"
}

/** What the screen shows over (or instead of) the video. */
private sealed interface Tuned {
    data object Tuning : Tuned
    /** A program is playing; [behind] once paused, until it ends or the viewer jumps to live. */
    data class Playing(val program: GuideProgram, val next: GuideProgram?) : Tuned
    /**
     * A break, before the first program, or a program that left the library; [track] is the
     * break's music playing now, if any.
     */
    data class Slate(
        val program: GuideProgram?,
        val next: GuideProgram?,
        val track: GuideProgram? = null,
    ) : Tuned
    data class Failed(val message: String) : Tuned
}

/**
 * Live TV: plays whatever is on the channel now from where it is now, by the server's clock.
 * Up and down change channel, OK pauses (then you're behind live) and jumps back to live.
 * Watch progress isn't written, so flipping past things doesn't fill Continue Watching.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LivePlayerScreen(
    onBackPressed: () -> Unit,
    viewModel: LivePlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val exoPlayer = rememberPlayer(context)
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val guide by viewModel.current.collectAsStateWithLifecycle()

    var tuned by remember { mutableStateOf<Tuned>(Tuned.Tuning) }
    var behind by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    // Bumped to show the controls again (after tuning); they hide as the video player's do.
    var bannerKey by remember { mutableIntStateOf(0) }
    val controlsState = rememberVideoPlayerState(hideSeconds = 4)
    // Bumped by "jump to live" to tune the same channel again.
    var liveKey by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf<Instant?>(null) }
    var trackPicker by remember { mutableStateOf<TrackPicker?>(null) }

    LaunchedEffect(bannerKey) { controlsState.showControls(!paused) }

    DisposableEffect(exoPlayer) {
        // Close to the second is fine; starting at the nearest keyframe tunes faster.
        exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        applySubtitlePreference(exoPlayer, viewModel.settings.subtitleLanguage)
        var audioChosen = false
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                audioChosen = false
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!audioChosen) {
                    audioChosen = selectBestAudioTrack(exoPlayer, viewModel.settings.audioLanguage)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
                // Paused by the viewer (OK, the remote's media keys, Assistant): behind live.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    behind = true
                    bannerKey++
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                view.keepScreenOn = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.toUserMessage()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) ended = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            view.keepScreenOn = false
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    DisposableEffect(exoPlayer) {
        // The remote's media keys and Assistant; declared after the player so it's released first.
        val mediaSession = MediaSession.Builder(context, exoPlayer)
            .setId("live-${System.nanoTime()}")
            .build()
        onDispose { mediaSession.release() }
    }

    // Tunes [channel] and follows its schedule: each program from where it is now, then the
    // break slate, then the next program, and so on.
    LaunchedEffect(channel, liveKey) {
        behind = false
        tuned = Tuned.Tuning
        bannerKey++
        while (isActive) {
            val g = try {
                viewModel.guide()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tuned = Tuned.Failed("Couldn't load the guide. Retrying…")
                delay(RetryMs)
                continue
            }
            val ch = g.channel(channel)
            if (ch == null) {
                exoPlayer.stop()
                tuned = Tuned.Failed("$channel isn't on right now.")
                delay(RetryMs)
                continue
            }
            when (val t = tune(ch, g.now())) {
                is Tune.Program -> {
                    val program = t.program
                    tuned = Tuned.Playing(program, t.next)
                    playbackError = null
                    ended = false
                    isBuffering = true
                    exoPlayer.setMediaItem(program.toMediaItem(), t.offset.inWholeMilliseconds)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    // Live: until the slot ends, even if the file runs long. Behind live: until
                    // the file ends, then back to whatever is on now.
                    while (!ended && (behind || g.now() < program.end)) delay(TickMs)
                    if (behind) {
                        behind = false
                        bannerKey++
                        continue
                    }
                    exoPlayer.stop()
                    // A file shorter than its slot leaves a gap until the slot ends.
                    if (g.now() < program.end) {
                        tuned = Tuned.Slate(null, t.next)
                        delay((program.end - g.now()).coerceIn(1.seconds, MaxWait))
                    }
                }
                is Tune.Music -> {
                    // Break music, joined where it is now, under the up-next slate.
                    val track = t.track
                    tuned = Tuned.Slate(null, t.next, track)
                    playbackError = null
                    ended = false
                    exoPlayer.volume = 1f
                    exoPlayer.setMediaItem(track.toMediaItem(), t.offset.inWholeMilliseconds)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    // The last track is cut off at the next program, so it fades out.
                    while (!ended && g.now() < track.end) {
                        exoPlayer.volume = t.volume(g.now())
                        delay(FadeTickMs)
                    }
                    exoPlayer.stop()
                    exoPlayer.volume = 1f
                    // a file a little shorter than its slot: quiet until the next starts
                    if (g.now() < track.end) delay((track.end - g.now()).coerceIn(Duration.ZERO, MaxWait))
                }
                is Tune.Slate -> {
                    exoPlayer.stop()
                    tuned = Tuned.Slate(t.program, t.next)
                    bannerKey++
                    val wait = t.until?.let { it - g.now() } ?: MaxWait
                    delay(wait.coerceIn(1.seconds, MaxWait))
                }
            }
        }
    }

    // The banner's times and the slate's countdown.
    LaunchedEffect(guide) {
        val g = guide ?: return@LaunchedEffect
        while (true) {
            now = g.now()
            delay(1_000)
        }
    }

    BackHandler(onBack = onBackPressed)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // the remote's channel buttons still step through the lineup
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_UP) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { viewModel.tune(-1); true }
                    KeyEvent.KEYCODE_CHANNEL_UP -> { viewModel.tune(1); true }
                    else -> false
                }
            }
            // As the video player's, without seeking: the arrows bring up the controls and OK
            // pauses (live, then you're behind) and shows them.
            .handleDPadKeyEvents(
                onUp = { controlsState.showControls(!paused) },
                onDown = { controlsState.showControls(!paused) },
                onEnter = {
                    if (tuned is Tuned.Playing && !paused) exoPlayer.pause()
                    controlsState.showControls(false)
                },
            )
            .focusable()
    ) {
        val presentation = rememberPresentationState(exoPlayer)
        PlayerSurface(
            player = exoPlayer,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier
                .align(Alignment.Center)
                .resizeWithContentScale(
                    contentScale = ContentScale.Fit,
                    sourceSizeDp = presentation.videoSizeDp,
                )
        )
        PlayerSubtitles(player = exoPlayer, modifier = Modifier.fillMaxSize())

        val time = now
        when (val t = tuned) {
            is Tuned.Slate -> SlateCard(t, time, Modifier.align(Alignment.Center))
            is Tuned.Failed -> Message(t.message, Modifier.align(Alignment.Center))
            is Tuned.Playing -> {
                val error = playbackError
                if (error != null) {
                    Message(error, Modifier.align(Alignment.Center))
                } else if (isBuffering) {
                    DelayedLoading(Modifier.align(Alignment.Center)) { Spinner() }
                }
            }
            Tuned.Tuning -> DelayedLoading(Modifier.align(Alignment.Center)) { Spinner() }
        }

        val focusRequester = remember { FocusRequester() }
        VideoPlayerOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            focusRequester = focusRequester,
            isPlaying = !paused && tuned is Tuned.Playing,
            isControlsVisible = controlsState.isControlsVisible,
            showControls = { controlsState.showControls(!paused) },
            controls = {
                LiveControls(
                    channel = channel,
                    tuned = tuned,
                    now = time,
                    paused = paused,
                    behind = behind,
                    focusRequester = focusRequester,
                    onShowControls = { controlsState.showControls(!paused) },
                    onPlayPause = {
                        if (paused) exoPlayer.play() else exoPlayer.pause()
                        controlsState.showControls(!paused)
                    },
                    onOpenTrackPicker = { trackPicker = it },
                    onLive = { liveKey++ },
                )
            },
        )
    }

    // Outside the overlay, so its hiding doesn't close the dialog. A choice is kept in the
    // settings, as the video player's are, so the next program uses the same language.
    trackPicker?.let { picker ->
        val offLabel = stringResource(R.string.subtitles_off)
        val options = remember(picker) {
            when (picker) {
                TrackPicker.Subtitles -> subtitleTrackOptions(exoPlayer, offLabel)
                else -> audioTrackOptions(exoPlayer)
            }
        }
        TrackPickerDialog(
            title = stringResource(if (picker == TrackPicker.Subtitles) R.string.subtitles else R.string.audio_tracks),
            options = options,
            emptyText = stringResource(R.string.no_tracks),
            onSelect = { option ->
                option.select(exoPlayer)
                if (picker == TrackPicker.Subtitles) {
                    viewModel.settings.setSubtitleLanguage(option.language ?: C.LANGUAGE_UNDETERMINED)
                } else {
                    normalizeLanguage(option.language)?.let(viewModel.settings::setAudioLanguage)
                }
                trackPicker = null
            },
            onDismissRequest = { trackPicker = null },
        )
    }
}

/**
 * The video player's controls for live TV: the title with the channel and a Live badge, Audio
 * and Subtitles where the video player has them (and Jump to live when behind), and in place of
 * the seek bar, play/pause and the program's progress between its start and end times.
 */
@Composable
private fun LiveControls(
    channel: String,
    tuned: Tuned,
    now: Instant?,
    paused: Boolean,
    behind: Boolean,
    focusRequester: FocusRequester,
    onShowControls: () -> Unit,
    onPlayPause: () -> Unit,
    onOpenTrackPicker: (TrackPicker) -> Unit,
    onLive: () -> Unit,
) {
    val program = (tuned as? Tuned.Playing)?.program ?: (tuned as? Tuned.Slate)?.program
    val next = (tuned as? Tuned.Playing)?.next ?: (tuned as? Tuned.Slate)?.next
    val playing = tuned is Tuned.Playing
    VideoPlayerMainFrame(
        mediaTitle = {
            Column {
                Text(
                    text = listOfNotNull(channel, program?.block?.takeIf { it.isNotEmpty() }).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                VideoPlayerMediaTitle(
                    title = when {
                        program != null -> program.displayTitle
                        next != null -> "Up next: ${next.displayTitle}"
                        else -> channel
                    },
                    secondaryText = listOfNotNull(
                        program?.subtitle?.takeIf { it.isNotEmpty() },
                        program?.rating?.takeIf { it.isNotEmpty() },
                    ).joinToString(" • "),
                    tertiaryText = when {
                        paused -> "Paused · Behind live"
                        behind -> "Behind live"
                        else -> ""
                    },
                    type = if (playing && !behind && !paused) VideoPlayerMediaTitleType.LIVE else VideoPlayerMediaTitleType.DEFAULT,
                )
            }
        },
        mediaActions = {
            Row(
                modifier = Modifier.padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (behind && playing) {
                    JumpToLiveButton(onShowControls = onShowControls, onClick = onLive)
                }
                if (playing) {
                    VideoPlayerControlsIcon(
                        icon = Icons.Default.Audiotrack,
                        isPlaying = !paused,
                        contentDescription = StringConstants.Composable.VideoPlayerControlAudioButton,
                        onShowControls = onShowControls,
                        onClick = { onOpenTrackPicker(TrackPicker.Audio) }
                    )
                    VideoPlayerControlsIcon(
                        icon = Icons.Default.ClosedCaption,
                        isPlaying = !paused,
                        contentDescription = StringConstants.Composable.VideoPlayerControlClosedCaptionsButton,
                        onShowControls = onShowControls,
                        onClick = { onOpenTrackPicker(TrackPicker.Subtitles) }
                    )
                }
            }
        },
        seeker = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VideoPlayerControlsIcon(
                        // the overlay focuses this when the controls come up (in a break too)
                        modifier = Modifier.focusRequester(focusRequester),
                        icon = if (paused || !playing) Icons.Default.PlayArrow else Icons.Default.Pause,
                        onClick = { if (playing) onPlayPause() },
                        isPlaying = !paused,
                        contentDescription = StringConstants.Composable.VideoPlayerControlPlayPauseButton,
                    )
                    if (program != null) {
                        VideoPlayerControllerText(text = program.start.localTime())
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            val fraction = now?.let { ((it - program.start) / program.duration).toFloat() } ?: 0f
                            ProgressBar(fraction, Modifier.fillMaxWidth())
                        }
                        VideoPlayerControllerText(
                            text = program.end.localTime() +
                                (now?.takeIf { program.kind != ProgramKind.OffAir }?.let { " · " + timeLeft(program.end - it) } ?: ""),
                        )
                    }
                }
                if (next != null && program != null) {
                    Text(
                        text = "Next · ${next.start.localTime()} · ${next.displayTitle}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

/**
 * Back to what the channel is showing now, after a pause put you behind: words, not an icon,
 * since it's live's own and only there some of the time. Sized like the icons beside it.
 */
@Composable
private fun JumpToLiveButton(onShowControls: () -> Unit, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // focused, the controls stay up, as with the icons
    LaunchedEffect(focused) { if (focused) onShowControls() }
    Surface(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        interactionSource = interactionSource,
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("Jump to live", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SlateCard(slate: Tuned.Slate, now: Instant?, modifier: Modifier = Modifier) {
    val next = slate.next
    val title = when {
        slate.program?.kind == ProgramKind.OffAir -> "Off Air"
        slate.program?.kind == ProgramKind.Reserved -> slate.program.title
        next != null -> "Up next"
        else -> "Nothing scheduled"
    }
    val lines = buildList {
        if (next != null) {
            add(next.displayTitle)
            if (now != null && next.start > now) {
                val left = next.start - now
                add("Starts in ${left.inWholeMinutes}:${(left.inWholeSeconds % 60).toString().padStart(2, '0')}")
            }
        }
    }
    Column(
        modifier
            .widthIn(max = 720.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val track = slate.track
        if (track != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                MusicImage(
                    url = track.imageUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    placeholderKey = track.title,
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
        lines.forEachIndexed { i, line ->
            Text(
                text = line,
                style = if (i == 0) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Message(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = modifier
            .widthIn(max = 640.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(32.dp),
    )
}

private fun GuideProgram.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(videoUri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).build())
    .build()

private const val TickMs = 500L
private const val FadeTickMs = 100L
private const val RetryMs = 30_000L
private val MaxWait = 5.minutes

@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: TakeoutGuideRepository,
    val settings: TakeoutSettings,
) : ViewModel() {
    private val liveGuide = LiveGuide(repository)

    private val _channel = MutableStateFlow(
        savedStateHandle.get<String>(LivePlayerScreen.ChannelBundleKey).orEmpty()
    )

    /** The channel being watched, by name. */
    val channel: StateFlow<String> = _channel.asStateFlow()

    private val _current = MutableStateFlow<Guide?>(null)

    /** The latest guide, for the banner's clock. */
    val current: StateFlow<Guide?> = _current.asStateFlow()

    /** The guide, reloaded when it gets stale. */
    suspend fun guide(): Guide = liveGuide.get().also { _current.value = it }

    /** Moves [delta] channels through the lineup, wrapping around. */
    fun tune(delta: Int) {
        val channels = _current.value?.channels ?: return
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { it.name == _channel.value }.coerceAtLeast(0)
        _channel.value = channels[(index + delta).mod(channels.size)].name
    }
}
