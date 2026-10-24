package com.google.jetstream.presentation.screens.music.player

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.jetstream.R
import com.google.jetstream.data.entities.music.MusicQueue
import com.google.jetstream.data.entities.music.QueueItem
import com.google.jetstream.data.music.TakeoutMusicRepository
import com.google.jetstream.data.takeout.TakeoutSessionEntryPoint
import com.google.jetstream.presentation.common.Error
import com.google.jetstream.presentation.common.PlayerLoading
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.screens.music.common.MusicImage
import com.google.jetstream.presentation.theme.JetStreamCardShape
import com.google.jetstream.presentation.utils.focusOnInitialVisibility
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object MusicPlayerScreen {
    const val QueueBundleKey = "queue"
}

/**
 * Plays a music queue while this screen is open (phase 1: playback stops on leaving it; a
 * background service comes later). Big cover, the track, a progress bar and prev/play/next.
 */
@Composable
fun MusicPlayerScreen(
    onBackPressed: () -> Unit,
    viewModel: MusicPlayerViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackPressed)
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        MusicPlayerState.Loading -> PlayerLoading(Modifier.fillMaxSize())
        MusicPlayerState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is MusicPlayerState.Ready -> NowPlaying(queue = s.queue)
    }
}

@Composable
private fun NowPlaying(queue: MusicQueue) {
    val context = LocalContext.current
    val view = LocalView.current
    val player = remember { buildMusicPlayer(context) }

    var index by remember { mutableIntStateOf(queue.startIndex) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        player.setMediaItems(queue.items.map { it.toMediaItem() }, queue.startIndex, 0L)
        player.prepare()
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
                error = null
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                view.keepScreenOn = playing
            }

            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName
            }
        }
        player.addListener(listener)
        // Remote media keys and Assistant ("pause", "next song") reach the player.
        val session = MediaSession.Builder(context, player)
            .setId("music-${System.nanoTime()}")
            .build()
        onDispose {
            view.keepScreenOn = false
            session.release()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(500)
        }
    }

    val item = queue.items.getOrNull(index) ?: return
    val next = queue.items.getOrNull(index + 1)
    val childPadding = rememberChildPadding()

    Box(Modifier.fillMaxSize()) {
        // The cover, dimmed, fills the screen behind everything.
        MusicImage(
            url = item.imageUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.25f),
            placeholderKey = queue.title,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = childPadding.start * 2),
        ) {
            MusicImage(
                url = item.imageUri,
                contentDescription = item.album,
                modifier = Modifier
                    .size(400.dp)
                    .clip(JetStreamCardShape),
                placeholderKey = item.album,
            )
            Column(Modifier.padding(start = 48.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = item.album,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                ProgressLine(positionMs, durationMs, Modifier.padding(top = 32.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    ControlButton(
                        icon = Icons.Default.SkipPrevious,
                        description = stringResource(R.string.music_previous),
                        onClick = { player.seekToPrevious() },
                    )
                    val firstFocus = remember { mutableStateOf(false) }
                    ControlButton(
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        description = stringResource(
                            if (isPlaying) R.string.music_pause else R.string.music_play
                        ),
                        large = true,
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.focusOnInitialVisibility(firstFocus),
                    )
                    ControlButton(
                        icon = Icons.Default.SkipNext,
                        description = stringResource(R.string.music_next),
                        onClick = { player.seekToNextMediaItem() },
                    )
                }
                val status = when {
                    error != null -> stringResource(R.string.music_cant_play, error!!)
                    next != null -> stringResource(R.string.music_up_next, next.title, next.artist)
                    else -> null
                }
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        val fraction =
            if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(formatTime(positionMs), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.weight(1f))
            Text(formatTime(durationMs), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(if (large) 72.dp else 56.dp),
        colors = IconButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(if (large) 40.dp else 28.dp),
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun buildMusicPlayer(context: Context): ExoPlayer {
    val factory = EntryPointAccessors
        .fromApplication(context.applicationContext, TakeoutSessionEntryPoint::class.java)
        .playerFactory()
    return factory.builder()
        // Music: pause for calls and other apps' audio, resume after.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        .build()
        .apply {
            playWhenReady = true
            factory.addDebugLogging(this)
        }
}

private fun QueueItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(imageUri.ifEmpty { null }?.let(Uri::parse))
                .build()
        )
        .build()

sealed interface MusicPlayerState {
    data object Loading : MusicPlayerState
    data object Error : MusicPlayerState
    data class Ready(val queue: MusicQueue) : MusicPlayerState
}

@HiltViewModel
class MusicPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    private val ref: QueueRef? =
        savedStateHandle.get<String>(MusicPlayerScreen.QueueBundleKey)?.let(QueueRef::parse)

    private val _state = MutableStateFlow<MusicPlayerState>(MusicPlayerState.Loading)
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val ref = ref ?: run {
            _state.value = MusicPlayerState.Error
            return
        }
        _state.value = MusicPlayerState.Loading
        viewModelScope.launch {
            _state.value = try {
                val queue = when (ref) {
                    is QueueRef.Album -> repository.albumQueue(ref.albumId, ref.startIndex)
                    is QueueRef.Artist ->
                        repository.artistQueue(ref.artistId, ref.mix, ref.startIndex)
                }
                if (queue.items.isEmpty()) MusicPlayerState.Error else MusicPlayerState.Ready(queue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MusicPlayerState.Error
            }
        }
    }
}
