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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.takeoutfm.shared.guide.GuideProgram
import com.takeoutfm.shared.guide.OnNow
import com.takeoutfm.shared.guide.ProgramKind
import com.takeoutfm.shared.guide.TakeoutGuideRepository
import com.takeoutfm.shared.guide.onNow
import com.takeoutfm.tv.data.takeout.TakeoutSettings
import com.takeoutfm.tv.presentation.common.DelayedLoading
import com.takeoutfm.tv.presentation.common.Spinner
import com.takeoutfm.tv.presentation.screens.music.podcasts.ProgressBar
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.PlayerSubtitles
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.rememberPlayer
import com.takeoutfm.tv.presentation.screens.videoPlayer.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
    /** A break, before the first program, or a program that left the library. */
    data class Slate(val program: GuideProgram?, val next: GuideProgram?) : Tuned
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
    // Bumped to show the banner again; it hides itself a few seconds after the last bump.
    var bannerKey by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    // Bumped by "jump to live" to tune the same channel again.
    var liveKey by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf<Instant?>(null) }

    LaunchedEffect(bannerKey) {
        bannerVisible = true
        delay(BannerMs)
        bannerVisible = false
    }

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
            val on: OnNow = onNow(ch, g.now())
            val program = on.program
            if (program != null && program.kind != ProgramKind.OffAir && program.videoUri.isNotEmpty()) {
                tuned = Tuned.Playing(program, on.next)
                playbackError = null
                ended = false
                isBuffering = true
                exoPlayer.setMediaItem(program.toMediaItem(), on.offset.inWholeMilliseconds)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                // Live: until the slot ends, even if the file runs long. Behind live: until the
                // file ends, then back to whatever is on now.
                while (!ended && (behind || g.now() < program.end)) delay(TickMs)
                if (behind) {
                    behind = false
                    bannerKey++
                    continue
                }
                exoPlayer.stop()
                // A file shorter than its slot leaves a gap until the slot ends.
                if (g.now() < program.end) {
                    tuned = Tuned.Slate(null, on.next)
                    delay((program.end - g.now()).coerceIn(1.seconds, MaxWait))
                }
            } else {
                exoPlayer.stop()
                tuned = Tuned.Slate(program, on.next)
                bannerKey++
                val until = program?.end ?: on.next?.start
                val wait = until?.let { it - g.now() } ?: MaxWait
                delay(wait.coerceIn(1.seconds, MaxWait))
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

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_UP) {
                    return@onKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        viewModel.tune(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        viewModel.tune(1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        when {
                            tuned !is Tuned.Playing -> bannerKey++
                            paused -> {
                                exoPlayer.play()
                                bannerKey++
                            }
                            behind -> liveKey++
                            else -> exoPlayer.pause()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        bannerKey++
                        true
                    }
                    else -> false
                }
            }
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

        AnimatedVisibility(
            visible = bannerVisible || paused,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Banner(
                channel = channel,
                number = viewModel.number(channel),
                tuned = tuned,
                now = time,
                paused = paused,
                behind = behind,
            )
        }
    }
}

@Composable
private fun Banner(
    channel: String,
    number: Int?,
    tuned: Tuned,
    now: Instant?,
    paused: Boolean,
    behind: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = listOfNotNull(number?.toString(), channel).joinToString("  "),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            val status = when {
                paused -> "Paused · Behind live"
                behind -> "Behind live · OK to jump to live"
                else -> null
            }
            if (status != null) {
                Text(status, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            }
        }
        val program = (tuned as? Tuned.Playing)?.program ?: (tuned as? Tuned.Slate)?.program
        val next = (tuned as? Tuned.Playing)?.next ?: (tuned as? Tuned.Slate)?.next
        if (program != null) {
            Text(
                text = program.displayTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                program.subtitle.takeIf { it.isNotEmpty() },
                program.rating.takeIf { it.isNotEmpty() },
                "${program.start.localTime()} – ${program.end.localTime()}",
                now?.takeIf { program.kind != ProgramKind.OffAir }?.let { timeLeft(program.end - it) },
            )
            Text(
                text = detail.joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (now != null && tuned is Tuned.Playing) {
                ProgressBar(((now - program.start) / program.duration).toFloat())
            }
        }
        if (next != null) {
            Text(
                text = "Next · ${next.start.localTime()} · ${next.displayTitle}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SlateCard(slate: Tuned.Slate, now: Instant?, modifier: Modifier = Modifier) {
    val next = slate.next
    val title = when {
        slate.program?.kind == ProgramKind.OffAir -> "Off Air"
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

private const val BannerMs = 5_000L
private const val TickMs = 500L
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

    /** The channel's place in the lineup, from 1. */
    fun number(name: String): Int? =
        _current.value?.channels?.indexOfFirst { it.name == name }?.takeIf { it >= 0 }?.plus(1)

    /** Moves [delta] channels through the lineup, wrapping around. */
    fun tune(delta: Int) {
        val channels = _current.value?.channels ?: return
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { it.name == _channel.value }.coerceAtLeast(0)
        _channel.value = channels[(index + delta).mod(channels.size)].name
    }
}
