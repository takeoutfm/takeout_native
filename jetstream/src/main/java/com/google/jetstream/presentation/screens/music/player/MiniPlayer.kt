package com.google.jetstream.presentation.screens.music.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.StateFlow

/**
 * The music that's playing, as a small card in a corner of every screen but the players:
 * cover, track and artist (opens Now Playing), and play/pause. Hidden with nothing queued.
 */
@Composable
fun MiniPlayer(
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MiniPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val item = state.current ?: return
    Surface(
        shape = JetStreamCardShape,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        ),
        modifier = modifier,
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
                            text = item.artist,
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
        }
    }
}

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    val player: MusicPlayer,
) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying> = player.state
}
