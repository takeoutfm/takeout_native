package com.takeoutfm.tv.presentation.screens.music.playlists

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.takeoutfm.shared.music.MusicPlaylist
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.screens.music.common.MusicButton
import com.takeoutfm.tv.presentation.screens.music.common.MusicListRow
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A dialog's card: [title], then [content]. */
@Composable
private fun DialogCard(title: String, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = JetStreamCardShape,
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                content()
            }
        }
    }
}

/** Asks [question] ([detail] under it); [confirm] is the button that does it. Cancel has focus. */
@Composable
fun ConfirmDialog(question: String, detail: String, confirm: String, onConfirm: () -> Unit, onDismissRequest: () -> Unit) {
    val cancel = remember { FocusRequester() }
    DialogCard(question, onDismissRequest) {
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 24.dp)) {
            MusicButton(text = stringResource(R.string.cancel), icon = Icons.Default.Close, onClick = onDismissRequest,
                        modifier = Modifier.focusRequester(cancel))
            MusicButton(text = confirm, icon = Icons.Default.Delete, onClick = onConfirm)
        }
    }
    LaunchedEffect(Unit) { cancel.requestFocus() }
}

/** Asks for a new playlist's name; [onName] gets it, trimmed and not empty. */
@Composable
fun NewPlaylistDialog(onName: (String) -> Unit, onDismissRequest: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val done = { if (name.isNotBlank()) onName(name.trim()) }
    DialogCard(stringResource(R.string.playlist_new), onDismissRequest) {
        Surface(
            onClick = { field.requestFocus() },
            shape = ClickableSurfaceDefaults.shape(JetStreamCardShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = JetStreamCardShape),
            ),
            modifier = Modifier.padding(top = 16.dp).width(480.dp),
        ) {
            Box(Modifier.padding(16.dp)) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
                if (name.isEmpty()) {
                    Text(stringResource(R.string.playlist_name), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 24.dp)) {
            MusicButton(text = stringResource(R.string.playlist_create), icon = Icons.Default.Add, primary = true, onClick = done)
        }
    }
    LaunchedEffect(Unit) { field.requestFocus() }
}

/**
 * Picks the playlist to add track [trackId] ([title]) to, or a new one (LIB-13); says which with
 * a toast. The list follows the shared playlists.
 */
@Composable
fun AddToPlaylistDialog(
    trackId: String,
    title: String,
    onDismissRequest: () -> Unit,
    viewModel: AddToPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val added: (String?) -> Unit = { name ->
        Toast.makeText(
            context,
            if (name != null) context.getString(R.string.playlist_added, name) else context.getString(R.string.playlist_cant_add),
            Toast.LENGTH_SHORT,
        ).show()
        onDismissRequest()
    }
    if (naming) {
        NewPlaylistDialog(
            onName = { name -> viewModel.addToNew(name, trackId, added) },
            onDismissRequest = { naming = false },
        )
        return
    }
    DialogCard(stringResource(R.string.playlist_add_title, title), onDismissRequest) {
        LazyColumn(
            contentPadding = PaddingValues(top = 16.dp),
            modifier = Modifier.width(520.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp),
        ) {
            item(key = "new") {
                MusicListRow(
                    imageUri = null,
                    placeholderKey = "new",
                    title = "+ " + stringResource(R.string.playlist_new),
                    subtitle = "",
                    onClick = { naming = true },
                    modifier = Modifier.focusRequester(first),
                )
            }
            items(playlists.orEmpty(), key = { it.id }) { playlist ->
                MusicListRow(
                    imageUri = null,
                    placeholderKey = playlist.id,
                    title = playlist.name,
                    subtitle = pluralStringResource(R.plurals.music_track_count, playlist.trackCount, playlist.trackCount),
                    onClick = { viewModel.add(playlist, trackId, added) },
                )
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    /** The playlists A–Z, kept current by every edit (decision 017); null until loaded. */
    val playlists: StateFlow<List<MusicPlaylist>?> = repository.currentPlaylists

    init {
        viewModelScope.launch { runCatching { repository.playlists() } }
    }

    /** Adds [trackId] to [playlist]; [done] gets its name, or null when it couldn't. */
    fun add(playlist: MusicPlaylist, trackId: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            done(attempt { repository.addTrackToPlaylist(playlist.id, trackId); playlist.name })
        }
    }

    /** Makes playlist [name] and adds [trackId] to it. */
    fun addToNew(name: String, trackId: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            done(attempt {
                val playlist = repository.createPlaylist(name)
                repository.addTrackToPlaylist(playlist.id, trackId)
                playlist.name
            })
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
