package com.takeoutfm.tv.presentation.screens.music.common

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.music.Track
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What a long press on a track offers (PLAY-4): Play Next puts it after the track playing, Add
 * to Queue at the end; with nothing queued, either starts it. Says which with a toast.
 */
@Composable
fun TrackMenuDialog(
    track: Track,
    onDismissRequest: () -> Unit,
    viewModel: TrackMenuViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    fun enqueue(last: Boolean) {
        viewModel.enqueue(track.id, last) { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        onDismissRequest()
    }
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = JetStreamCardShape,
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(track.artist, track.album).filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    MusicButton(
                        text = stringResource(R.string.music_play_next),
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        primary = true,
                        onClick = { enqueue(last = false) },
                        modifier = Modifier.focusRequester(first),
                    )
                    MusicButton(
                        text = stringResource(R.string.music_add_to_queue),
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        onClick = { enqueue(last = true) },
                    )
                }
            }
        }
        LaunchedEffect(Unit) { first.requestFocus() }
    }
}

@HiltViewModel
class TrackMenuViewModel @Inject constructor(
    private val player: MusicPlayer,
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    /** Queues track [trackId] next (or [last]); [onDone] gets the message to show. */
    fun enqueue(trackId: String, last: Boolean, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            onDone(
                try {
                    // Just the track: its own queue goes on with similar tracks.
                    player.enqueue(repository.trackQueue(trackId).items.take(1), last)
                    if (last) R.string.music_added_to_queue else R.string.music_playing_next
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    R.string.music_cant_queue
                }
            )
        }
    }
}
