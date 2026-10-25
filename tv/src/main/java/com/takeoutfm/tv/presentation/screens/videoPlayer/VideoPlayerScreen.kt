/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.takeoutfm.tv.presentation.screens.videoPlayer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.takeoutfm.tv.R
import com.takeoutfm.tv.data.entities.Movie
import com.takeoutfm.tv.data.entities.MovieDetails
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.tv.data.takeout.TakeoutSettings
import com.takeoutfm.tv.presentation.common.DelayedLoading
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.PlayerLoading
import com.takeoutfm.tv.presentation.common.Spinner
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.PlayerSubtitles
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.TrackPicker
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.TrackPickerDialog
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerControls
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerOverlay
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerPulse
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerPulse.Type.BACK
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerPulse.Type.FORWARD
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerPulseState
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.VideoPlayerState
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.applySubtitlePreference
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.audioTrackOptions
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.normalizeLanguage
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.rememberPlayer
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.rememberVideoPlayerPulseState
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.rememberVideoPlayerState
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.selectBestAudioTrack
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.speedOptions
import com.takeoutfm.tv.presentation.screens.videoPlayer.components.subtitleTrackOptions
import com.takeoutfm.tv.presentation.utils.handleDPadKeyEvents
import kotlinx.coroutines.delay

object VideoPlayerScreen {
    const val MovieIdBundleKey = "movieId"

    /** Optional: true to ignore saved progress and play from the beginning. */
    const val StartOverBundleKey = "startOver"
}

/**
 * [Work in progress] A composable screen for playing a video.
 *
 * @param onBackPressed The callback to invoke when the user presses the back button.
 * @param videoPlayerScreenViewModel The view model for the video player screen.
 */
@Composable
fun VideoPlayerScreen(
    onBackPressed: () -> Unit,
    onPlayEpisode: (episodeId: String) -> Unit,
    videoPlayerScreenViewModel: VideoPlayerScreenViewModel = hiltViewModel()
) {
    val uiState by videoPlayerScreenViewModel.uiState.collectAsStateWithLifecycle()

    when (val s = uiState) {
        is VideoPlayerScreenUiState.Loading -> {
            PlayerLoading(modifier = Modifier.fillMaxSize())
        }

        is VideoPlayerScreenUiState.Error -> {
            Error(modifier = Modifier.fillMaxSize(), onRetry = videoPlayerScreenViewModel::retry)
        }

        is VideoPlayerScreenUiState.Done -> {
            VideoPlayerScreenContent(
                movieDetails = s.movieDetails,
                progress = videoPlayerScreenViewModel.progress,
                startOver = videoPlayerScreenViewModel.startOver,
                settings = videoPlayerScreenViewModel.settings,
                onBackPressed = onBackPressed,
                onPlayEpisode = onPlayEpisode
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreenContent(
    movieDetails: MovieDetails,
    progress: TakeoutProgress,
    startOver: Boolean,
    settings: TakeoutSettings,
    onBackPressed: () -> Unit,
    onPlayEpisode: (episodeId: String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val exoPlayer = rememberPlayer(context)

    val videoPlayerState = rememberVideoPlayerState(
        hideSeconds = 4,
    )

    val playbackError = remember { mutableStateOf<String?>(null) }
    // True until the first frames are ready, and whenever playback stalls to buffer.
    val isBuffering = remember { mutableStateOf(true) }
    // The player's isPlaying as Compose state, so the overlay and play/pause button follow
    // changes made outside the UI (remote media keys, Assistant) as well as their own.
    val isPlayingState = remember { mutableStateOf(exoPlayer.isPlaying) }
    // Only save progress once playback really started, so a failed load never resets it to 0.
    val hasPlayed = remember { mutableStateOf(false) }
    val saveProgress = remember(exoPlayer, movieDetails) {
        {
            if (hasPlayed.value && playbackError.value == null) {
                progress.report(movieDetails.etag, exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
    }

    LaunchedEffect(exoPlayer, movieDetails) {
        if (movieDetails.videoUri.isEmpty()) {
            playbackError.value = "The server has no playable file for this title."
            return@LaunchedEffect
        }
        // Only the selected title: list items don't carry a stream URL until details load.
        val resumeMs = if (startOver) 0 else progress.resumePositionMs(movieDetails.etag)
        applySubtitlePreference(exoPlayer, settings.subtitleLanguage)
        exoPlayer.setMediaItem(movieDetails.intoMediaItem(), resumeMs)
        exoPlayer.prepare()
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(ProgressSaveIntervalMs)
            if (exoPlayer.isPlaying) saveProgress()
        }
    }

    DisposableEffect(exoPlayer) {
        // Pick the best audio track once per title; after that the user's ♪ choice stands.
        var audioChosen = false
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (!audioChosen) {
                    audioChosen = selectBestAudioTrack(exoPlayer, settings.audioLanguage)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingState.value = isPlaying
                if (isPlaying) hasPlayed.value = true else saveProgress()
                // Keep the TV's screensaver away only while video is actually playing;
                // paused or stopped, the normal timeout applies again.
                view.keepScreenOn = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError.value = error.toUserMessage()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering.value = playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            view.keepScreenOn = false
            exoPlayer.removeListener(listener)
            saveProgress()
            exoPlayer.release()
        }
    }

    DisposableEffect(exoPlayer) {
        // Lets the remote's media keys (play/pause, fast-forward, rewind) and Assistant voice
        // commands ("pause", "skip ahead 30 seconds") control the player. The id only has to be
        // unique while two player screens briefly overlap, e.g. going to the next episode.
        // Declared after the player's effect so it's disposed first: session before player.
        val mediaSession = MediaSession.Builder(context, exoPlayer)
            .setId("player-${System.nanoTime()}")
            .build()
        onDispose { mediaSession.release() }
    }

    BackHandler(onBack = onBackPressed)

    val pulseState = rememberVideoPlayerPulseState()
    var trackPicker by remember { mutableStateOf<TrackPicker?>(null) }

    Box(
        Modifier
            // Black bars around video that isn't the screen's shape.
            .background(Color.Black)
            .dPadEvents(
                exoPlayer,
                videoPlayerState,
                pulseState
            )
            .focusable()
    ) {
        // Sized to the video's own shape (pixel aspect ratio included), letterboxed or
        // pillarboxed in black: without a source size the surface fills the screen and
        // stretches anything that isn't 16:9 (e.g. 4:3 classics, 2.39:1 scope films).
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

        // Full screen rather than in the overlay's slot, so cues don't move with the controls.
        PlayerSubtitles(player = exoPlayer, modifier = Modifier.fillMaxSize())

        val focusRequester = remember { FocusRequester() }
        VideoPlayerOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            focusRequester = focusRequester,
            isPlaying = isPlayingState.value,
            isControlsVisible = videoPlayerState.isControlsVisible,
            centerButton = { VideoPlayerPulse(pulseState) },
            subtitles = {},
            showControls = videoPlayerState::showControls,
            controls = {
                VideoPlayerControls(
                    player = exoPlayer,
                    isPlaying = isPlayingState.value,
                    movieDetails = movieDetails,
                    focusRequester = focusRequester,
                    onShowControls = { videoPlayerState.showControls(exoPlayer.isPlaying) },
                    onOpenTrackPicker = { trackPicker = it },
                    onPlayEpisode = onPlayEpisode,
                )
            }
        )

        if (isBuffering.value && playbackError.value == null) {
            // Re-entered on every stall, so each gets the short delay before showing.
            DelayedLoading(Modifier.align(Alignment.Center)) { Spinner() }
        }

        playbackError.value?.let { message ->
            PlaybackErrorMessage(message, Modifier.align(Alignment.Center))
        }
    }

    // Outside the overlay: the controls auto-hide, which would otherwise close the dialog.
    trackPicker?.let { picker ->
        val offLabel = stringResource(R.string.subtitles_off)
        val normalLabel = stringResource(R.string.speed_normal)
        val options = remember(picker) {
            when (picker) {
                TrackPicker.Audio -> audioTrackOptions(exoPlayer)
                TrackPicker.Subtitles -> subtitleTrackOptions(exoPlayer, offLabel)
                TrackPicker.Speed -> speedOptions(exoPlayer, normalLabel)
            }
        }
        TrackPickerDialog(
            title = stringResource(
                when (picker) {
                    TrackPicker.Audio -> R.string.audio_tracks
                    TrackPicker.Subtitles -> R.string.subtitles
                    TrackPicker.Speed -> R.string.playback_speed
                }
            ),
            options = options,
            emptyText = stringResource(R.string.no_tracks),
            onSelect = { option ->
                option.select(exoPlayer)
                // Remember the subtitle choice for the next title.
                // Remember the audio language for the next title's best-track choice.
                if (picker == TrackPicker.Subtitles) {
                    settings.setSubtitleLanguage(option.language ?: C.LANGUAGE_UNDETERMINED)
                } else if (picker == TrackPicker.Audio) {
                    normalizeLanguage(option.language)?.let(settings::setAudioLanguage)
                }
                trackPicker = null
            },
            onDismissRequest = { trackPicker = null }
        )
    }
}

@Composable
private fun PlaybackErrorMessage(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(max = 640.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.playback_error_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.85f)
        )
        Text(
            text = stringResource(R.string.playback_error_back),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlaybackException.toUserMessage(): String {
    if (this is ExoPlaybackException && type == ExoPlaybackException.TYPE_RENDERER) {
        val format = rendererFormat
        val what = listOfNotNull(
            format?.sampleMimeType?.substringAfter('/')?.uppercase(),
            format?.codecs
        ).joinToString(" ")
        return if (what.isEmpty()) {
            "This device can't decode this video."
        } else {
            "This device can't decode this video ($what)."
        }
    }
    val http = generateSequence(cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
    return when {
        http != null -> "The server returned HTTP ${http.responseCode} for this video."
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Couldn't reach the server. Check your connection."
        else -> "Playback failed ($errorCodeName)."
    }
}

private const val ProgressSaveIntervalMs = 30_000L

private fun Modifier.dPadEvents(
    exoPlayer: ExoPlayer,
    videoPlayerState: VideoPlayerState,
    pulseState: VideoPlayerPulseState
): Modifier = this.handleDPadKeyEvents(
    onLeft = {
        if (!videoPlayerState.isControlsVisible) {
            exoPlayer.seekBack()
            pulseState.setType(BACK)
        }
    },
    onRight = {
        if (!videoPlayerState.isControlsVisible) {
            exoPlayer.seekForward()
            pulseState.setType(FORWARD)
        }
    },
    onUp = { videoPlayerState.showControls() },
    onDown = { videoPlayerState.showControls() },
    onEnter = {
        exoPlayer.pause()
        videoPlayerState.showControls()
    }
)

private fun MovieDetails.intoMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setUri(videoUri)
        // Shown by the system for the media session (e.g. what Assistant says is playing).
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
        .setSubtitleConfigurations(
            if (subtitleUri == null) {
                emptyList()
            } else {
                listOf(
                    MediaItem.SubtitleConfiguration
                        .Builder(Uri.parse(subtitleUri))
                        .setMimeType("application/vtt")
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        ).build()
}

private fun Movie.intoMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setUri(videoUri)
        .setSubtitleConfigurations(
            if (subtitleUri == null) {
                emptyList()
            } else {
                listOf(
                    MediaItem.SubtitleConfiguration
                        .Builder(Uri.parse(subtitleUri))
                        .setMimeType("application/vtt")
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        )
        .build()
}
