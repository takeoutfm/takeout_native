package com.takeoutfm.tv.presentation.screens.music.playlists

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.common.DetailsSkeleton
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.screens.music.common.MusicButton
import com.takeoutfm.tv.presentation.screens.music.common.MusicListRow
import com.takeoutfm.tv.presentation.screens.music.player.QueueRef
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object PlaylistScreen {
    const val PlaylistIdBundleKey = "playlistId"
    const val PlaylistNameBundleKey = "name"
}

/**
 * A playlist (or Favorite Tracks) as an album's page (LIB-13): its art, name and track count,
 * Play and Delete Playlist (asking first), then its tracks. Selecting one plays from it; a long
 * press offers Play Next, Add to Queue and Remove from Playlist (asking first).
 */
@Composable
fun PlaylistScreen(
    onPlay: (QueueRef) -> Unit,
    onBackPressed: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackPressed)
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val covers by viewModel.covers.collectAsStateWithLifecycle()
    val isFavorites = viewModel.isFavorites
    var menuItem by remember { mutableStateOf<Pair<Int, QueueItem>?>(null) }
    var removing by remember { mutableStateOf<Pair<Int, QueueItem>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val failed = { Toast.makeText(context, R.string.playlist_cant_change, Toast.LENGTH_SHORT).show() }

    menuItem?.let { (index, item) ->
        PlaylistItemMenu(
            item = item,
            removable = !isFavorites,
            onEnqueue = { last ->
                menuItem = null
                viewModel.enqueue(item, last)
                Toast.makeText(context, if (last) R.string.music_added_to_queue else R.string.music_playing_next, Toast.LENGTH_SHORT).show()
            },
            onRemove = {
                menuItem = null
                removing = index to item
            },
            onDismissRequest = { menuItem = null },
        )
    }
    removing?.let { (index, item) ->
        ConfirmDialog(
            question = stringResource(R.string.playlist_remove_question, item.title),
            detail = stringResource(R.string.playlist_remove_detail, viewModel.name),
            confirm = stringResource(R.string.playlist_remove_button),
            onConfirm = {
                removing = null
                viewModel.remove(index) { ok -> if (!ok) failed() }
            },
            onDismissRequest = { removing = null },
        )
    }
    if (deleting) {
        ConfirmDialog(
            question = stringResource(R.string.playlist_delete_question, viewModel.name),
            detail = stringResource(R.string.playlist_delete_detail),
            confirm = stringResource(R.string.playlist_delete_button),
            onConfirm = {
                deleting = false
                viewModel.delete { ok -> if (ok) onBackPressed() else failed() }
            },
            onDismissRequest = { deleting = false },
        )
    }

    when (val s = state) {
        PlaylistState.Loading -> DetailsSkeleton(Modifier.fillMaxSize())
        PlaylistState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is PlaylistState.Ready -> {
            val childPadding = rememberChildPadding()
            val play = remember { FocusRequester() }
            val items = s.queue.items
            LazyColumn(
                contentPadding = PaddingValues(top = childPadding.top, bottom = 108.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = childPadding.start, vertical = 24.dp),
                    ) {
                        val art = Modifier.size(220.dp).clip(JetStreamCardShape)
                        if (isFavorites) FavoriteTracksArt(art) else PlaylistMosaic(covers, art)
                        Column(Modifier.padding(start = 32.dp)) {
                            Text(
                                text = viewModel.name,
                                style = MaterialTheme.typography.displaySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = pluralStringResource(R.plurals.music_track_count, items.size, items.size),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(top = 24.dp),
                            ) {
                                if (items.isNotEmpty()) {
                                    MusicButton(
                                        text = stringResource(R.string.music_play),
                                        icon = Icons.Outlined.PlayArrow,
                                        primary = true,
                                        onClick = { onPlay(viewModel.ref(0)) },
                                        modifier = Modifier.focusRequester(play),
                                    )
                                }
                                if (!isFavorites) {
                                    MusicButton(
                                        text = stringResource(R.string.playlist_delete),
                                        icon = Icons.Default.Delete,
                                        onClick = { deleting = true },
                                        modifier = if (items.isEmpty()) Modifier.focusRequester(play) else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
                itemsIndexed(items, key = { index, item -> "$index-${item.etag}" }) { index, item ->
                    MusicListRow(
                        imageUri = item.imageUri,
                        placeholderKey = item.album,
                        title = item.title,
                        subtitle = listOf(item.artist, item.album).filter { it.isNotEmpty() }.joinToString(" · "),
                        onClick = { onPlay(viewModel.ref(index)) },
                        onLongClick = { menuItem = index to item },
                        modifier = Modifier.padding(horizontal = childPadding.start),
                    )
                }
            }
            LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
        }
    }
}

/** A playlist track's long-press menu: Play Next, Add to Queue, and Remove from Playlist. */
@Composable
private fun PlaylistItemMenu(
    item: QueueItem,
    removable: Boolean,
    onEnqueue: (last: Boolean) -> Unit,
    onRemove: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = JetStreamCardShape,
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(item.artist, item.album).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 24.dp)) {
                    MusicButton(
                        text = stringResource(R.string.music_play_next),
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        primary = true,
                        onClick = { onEnqueue(false) },
                        modifier = Modifier.focusRequester(first),
                    )
                    MusicButton(
                        text = stringResource(R.string.music_add_to_queue),
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        onClick = { onEnqueue(true) },
                    )
                    if (removable) {
                        MusicButton(
                            text = stringResource(R.string.playlist_remove),
                            icon = Icons.Default.RemoveCircleOutline,
                            onClick = onRemove,
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) { first.requestFocus() }
    }
}

sealed interface PlaylistState {
    data object Loading : PlaylistState
    data object Error : PlaylistState
    data class Ready(val queue: MusicQueue) : PlaylistState
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TakeoutMusicRepository,
    private val player: MusicPlayer,
) : ViewModel() {
    val playlistId: String = savedStateHandle[PlaylistScreen.PlaylistIdBundleKey] ?: ""
    // trimmed: names can come with spaces at the end ("Gorillaz ")
    val name: String = savedStateHandle.get<String>(PlaylistScreen.PlaylistNameBundleKey).orEmpty().trim()
    val isFavorites = playlistId == FavoritesPlaylistId

    private val _state = MutableStateFlow<PlaylistState>(PlaylistState.Loading)
    val state: StateFlow<PlaylistState> = _state.asStateFlow()

    private val _covers = MutableStateFlow<List<String>>(emptyList())
    val covers: StateFlow<List<String>> = _covers.asStateFlow()

    init {
        load()
        // A track added or removed (here or anywhere) changes the shared count: load again
        // (decision 017), keeping what's shown meanwhile.
        if (!isFavorites) {
            viewModelScope.launch {
                repository.currentPlaylists
                    .map { list -> list?.firstOrNull { it.id == playlistId }?.trackCount }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { count ->
                        val shown = (_state.value as? PlaylistState.Ready)?.queue?.items?.size
                        if (count != null && count != shown) load(quietly = true)
                    }
            }
        }
    }

    fun load(quietly: Boolean = false) {
        if (!quietly) _state.value = PlaylistState.Loading
        viewModelScope.launch {
            _state.value = try {
                val queue = if (isFavorites) repository.favoritesQueue() else repository.playlistQueue(playlistId)
                if (!isFavorites) _covers.value = repository.keepCovers(playlistId, queue)
                PlaylistState.Ready(queue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (quietly && _state.value is PlaylistState.Ready) _state.value else PlaylistState.Error
            }
        }
    }

    /** What plays this list from the [index]th track. */
    fun ref(index: Int): QueueRef = if (isFavorites) QueueRef.Favorites(index) else QueueRef.Playlist(playlistId, index)

    /** [item] as it is, after the track playing or at the end ([last]). */
    fun enqueue(item: QueueItem, last: Boolean) = player.enqueue(listOf(item), last)

    /** Removes the track at [index]; the shared count then reloads the page. */
    fun remove(index: Int, done: (Boolean) -> Unit) = edit(done) { repository.removeFromPlaylist(playlistId, index) }

    fun delete(done: (Boolean) -> Unit) = edit(done) { repository.deletePlaylist(playlistId) }

    private fun edit(done: (Boolean) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            done(
                try {
                    block()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            )
        }
    }
}
