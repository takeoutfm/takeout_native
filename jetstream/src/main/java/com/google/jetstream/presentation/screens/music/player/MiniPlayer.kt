package com.google.jetstream.presentation.screens.music.player

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.google.jetstream.R
import com.google.jetstream.playback.MusicPlayer
import com.google.jetstream.playback.NowPlaying
import com.google.jetstream.presentation.screens.music.common.MusicImage
import com.google.jetstream.presentation.theme.JetStreamCardShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The music that's playing, as a small card in a corner of every screen but the players:
 * cover, track and artist (opens Now Playing), play/pause, and ✕ to stop the music and close
 * it. Hidden with nothing queued. For a live stream, what's on air shows under the station.
 */
@Composable
fun MiniPlayer(
    onOpenNowPlaying: () -> Unit,
    /**
     * Up (and Left from the card): back to the screen's last focused card or row; false when
     * there's none, and the D-pad's normal search is used.
     */
    onReturnFocus: () -> Boolean,
    /** Tries to hand focus back to the page; false while it has nothing focusable. */
    isContentFocusable: () -> Boolean,
    /** `SystemClock.uptimeMillis()` of the last D-pad direction press. */
    lastNavigationKeyAt: () -> Long,
    modifier: Modifier = Modifier,
    viewModel: MiniPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val item = state.current ?: return
    var cardFocused by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    // Focus that arrived without a D-pad press fell in (the page under it is changing): give it
    // back to the page as soon as the page can take it.
    LaunchedEffect(hasFocus) {
        if (!hasFocus) return@LaunchedEffect
        if (SystemClock.uptimeMillis() - lastNavigationKeyAt() < MovedInWindowMs) return@LaunchedEffect
        withTimeoutOrNull(FallenInRetryMs) {
            while (!isContentFocusable()) delay(100)
        }
    }
    Surface(
        shape = JetStreamCardShape,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        ),
        modifier = modifier
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent {
                val code = it.nativeKeyEvent.keyCode
                val back = code == KeyEvent.KEYCODE_DPAD_UP ||
                    (code == KeyEvent.KEYCODE_DPAD_LEFT && cardFocused)
                back && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && onReturnFocus()
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(6.dp),
        ) {
            Surface(
                onClick = onOpenNowPlaying,
                shape = ClickableSurfaceDefaults.shape(JetStreamCardShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                ),
                modifier = Modifier.onFocusChanged { cardFocused = it.isFocused },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(6.dp),
                ) {
                    MusicImage(
                        url = item.imageUri,
                        contentDescription = stringResource(R.string.music_now_playing),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(JetStreamCardShape),
                        placeholderKey = item.album,
                    )
                    Column(
                        Modifier
                            .padding(start = 12.dp)
                            .width(220.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.streamTitle ?: item.artist,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(onClick = viewModel.player::playPause) {
                Icon(
                    imageVector = if (state.playWhenReady) {
                        Icons.Default.Pause
                    } else {
                        Icons.Default.PlayArrow
                    },
                    contentDescription = stringResource(
                        if (state.playWhenReady) R.string.music_pause else R.string.music_play
                    ),
                )
            }
            IconButton(onClick = viewModel.player::stop) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.music_stop),
                )
            }
        }
    }
}

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    val player: MusicPlayer,
) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying> = player.state
}

/** Focus gained this soon after a D-pad press was moved in by the user. */
private const val MovedInWindowMs = 800L

/** How long to keep trying to give fallen-in focus back while the page loads. */
private const val FallenInRetryMs = 5_000L
