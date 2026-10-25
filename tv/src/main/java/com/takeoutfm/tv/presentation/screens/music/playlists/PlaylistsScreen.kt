package com.takeoutfm.tv.presentation.screens.music.playlists

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.takeoutfm.tv.R
import com.takeoutfm.tv.data.entities.music.MusicPlaylist
import com.takeoutfm.tv.data.music.TakeoutMusicRepository
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.PosterGridSkeleton
import com.takeoutfm.tv.presentation.common.TileGrid
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.common.MusicMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The user's playlists A–Z, as name tiles (playlists have no artwork); selecting one plays it. */
@Composable
fun PlaylistsScreen(
    onPlaylistClick: (MusicPlaylist) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        PlaylistsState.Loading -> PosterGridSkeleton(Modifier.fillMaxSize(), aspectRatio = 1f)
        PlaylistsState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is PlaylistsState.Ready -> if (s.playlists.isEmpty()) {
            MusicMessage(stringResource(R.string.music_no_playlists))
        } else {
            val trackCounts = s.playlists.associate { playlist ->
                playlist.id to pluralStringResource(
                    R.plurals.music_track_count, playlist.trackCount, playlist.trackCount
                )
            }
            TileGrid(
                items = s.playlists,
                key = { it.id },
                itemTitle = { trackCounts[it.id].orEmpty() },
                itemImage = { playlist, imageModifier ->
                    MusicImage(
                        url = "",
                        contentDescription = playlist.name,
                        modifier = imageModifier,
                        placeholderKey = playlist.id,
                        fallbackText = playlist.name,
                    )
                },
                onItemClick = onPlaylistClick,
                aspectRatio = ItemDirection.Square.aspectRatio,
                onScroll = onScroll,
                isTopBarVisible = isTopBarVisible,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

sealed interface PlaylistsState {
    data object Loading : PlaylistsState
    data object Error : PlaylistsState
    data class Ready(val playlists: List<MusicPlaylist>) : PlaylistsState
}

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<PlaylistsState>(PlaylistsState.Loading)
    val state: StateFlow<PlaylistsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = PlaylistsState.Loading
        viewModelScope.launch {
            _state.value = try {
                PlaylistsState.Ready(repository.playlists())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaylistsState.Error
            }
        }
    }
}
