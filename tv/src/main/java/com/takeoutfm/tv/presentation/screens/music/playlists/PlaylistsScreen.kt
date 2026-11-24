package com.takeoutfm.tv.presentation.screens.music.playlists

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.takeoutfm.shared.music.MusicPlaylist
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.PosterGridSkeleton
import com.takeoutfm.tv.presentation.common.TileGrid
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Favorite Tracks tile's (and page's) playlist id. */
const val FavoritesPlaylistId = "favorites"

/** The New Playlist tile's id. */
private const val NewId = "new"

/**
 * The playlists as covers (LIB-13), as on the Mac, phone and Linux: New Playlist, then Favorite
 * Tracks (a heart), then each playlist A–Z as a mosaic of its first album covers, its track
 * count under the focused one. Selecting one opens its page. The list follows the shared
 * playlists (decision 017): one made, deleted or edited anywhere shows at once.
 */
@Composable
fun PlaylistsScreen(
    onPlaylistClick: (id: String, name: String) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val covers by viewModel.covers.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NewPlaylistDialog(
            onName = { name ->
                naming = false
                viewModel.create(name) { ok ->
                    if (!ok) Toast.makeText(context, R.string.playlist_cant_change, Toast.LENGTH_SHORT).show()
                }
            },
            onDismissRequest = { naming = false },
        )
    }
    val list = playlists
    when {
        list == null && failed -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        list == null -> PosterGridSkeleton(Modifier.fillMaxSize(), aspectRatio = 1f)
        else -> {
            val favoritesName = stringResource(R.string.music_favorite_tracks_playlist)
            val tiles = buildList {
                add(MusicPlaylist(NewId, stringResource(R.string.playlist_new), 0))
                // No tile for no favorites.
                if (favorites != 0) add(MusicPlaylist(FavoritesPlaylistId, favoritesName, favorites ?: 0))
                addAll(list.map { it.copy(name = it.name.trim()) })
            }
            val counts = tiles.associate { playlist ->
                playlist.id to when (playlist.id) {
                    NewId -> playlist.name
                    else -> pluralStringResource(R.plurals.music_track_count, playlist.trackCount, playlist.trackCount)
                }
            }
            TileGrid(
                items = tiles,
                key = { it.id },
                itemTitle = { if (it.id == NewId) it.name else "${it.name} · ${counts[it.id].orEmpty()}" },
                itemImage = { playlist, imageModifier ->
                    when (playlist.id) {
                        NewId -> NewPlaylistArt(imageModifier)
                        FavoritesPlaylistId -> FavoriteTracksArt(imageModifier)
                        else -> PlaylistMosaic(covers[playlist.id].orEmpty(), imageModifier)
                    }
                },
                onItemClick = { if (it.id == NewId) naming = true else onPlaylistClick(it.id, it.name) },
                aspectRatio = ItemDirection.Square.aspectRatio,
                onScroll = onScroll,
                isTopBarVisible = isTopBarVisible,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    /** The playlists A–Z, kept current by every edit; null until first loaded. */
    val playlists: StateFlow<List<MusicPlaylist>?> = repository.currentPlaylists

    private val _favoriteCount = MutableStateFlow<Int?>(null)

    /** Favorite tracks; null if they couldn't be counted. */
    val favoriteCount: StateFlow<Int?> = _favoriteCount.asStateFlow()

    private val _covers = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Each playlist's mosaic covers, by id, as they load. */
    val covers: StateFlow<Map<String, List<String>>> = _covers.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    init {
        load()
        // Covers for each playlist, again when its track count changes (the core caches them by count).
        viewModelScope.launch {
            repository.currentPlaylists.collect { list ->
                list.orEmpty().forEach { playlist ->
                    launch {
                        val covers = runCatching { repository.playlistCovers(playlist.id, playlist.trackCount) }.getOrNull()
                        if (covers != null) _covers.update { it + (playlist.id to covers) }
                    }
                }
            }
        }
    }

    fun load() {
        _failed.value = false
        viewModelScope.launch {
            try {
                repository.playlists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _failed.value = true
            }
        }
        viewModelScope.launch {
            _favoriteCount.value = runCatching { repository.favorites().tracks.size }.getOrNull()
        }
    }

    /** Makes playlist [name] (it shows through the shared list); [done] says whether it could. */
    fun create(name: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            done(
                try {
                    repository.createPlaylist(name)
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
