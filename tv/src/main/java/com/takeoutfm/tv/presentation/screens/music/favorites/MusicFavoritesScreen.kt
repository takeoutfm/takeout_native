package com.takeoutfm.tv.presentation.screens.music.favorites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.takeoutfm.tv.R
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.MusicFavorites
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.MusicHomeSkeleton
import com.takeoutfm.tv.presentation.common.TileRow
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.screens.music.common.MusicButton
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.common.MusicMessage
import com.takeoutfm.tv.presentation.screens.music.common.MusicTabColumn
import com.takeoutfm.tv.presentation.screens.music.common.MusicTrackRow
import com.takeoutfm.tv.presentation.screens.music.player.QueueRef
import com.takeoutfm.tv.presentation.utils.RestoreRememberedFocus
import com.takeoutfm.tv.presentation.utils.rememberFocus
import com.takeoutfm.tv.presentation.utils.rememberFocusMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Favorite artists and tracks; Play all plays the tracks. */
@Composable
fun MusicFavoritesScreen(
    onArtistClick: (Artist) -> Unit,
    onPlay: (QueueRef) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: MusicFavoritesViewModel = hiltViewModel(),
) {
    // Favorites change elsewhere (artist pages, Now Playing), so refresh on every visit.
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        FavoritesState.Loading -> MusicHomeSkeleton(Modifier.fillMaxSize())
        FavoritesState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is FavoritesState.Ready -> {
            val favorites = s.favorites
            if (favorites.artists.isEmpty() && favorites.tracks.isEmpty()) {
                MusicMessage(stringResource(R.string.music_no_favorites))
            } else {
                FavoritesContent(
                    favorites, viewModel, onArtistClick, onPlay, onScroll, isTopBarVisible
                )
            }
        }
    }
}

@Composable
private fun FavoritesContent(
    favorites: MusicFavorites,
    viewModel: MusicFavoritesViewModel,
    onArtistClick: (Artist) -> Unit,
    onPlay: (QueueRef) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
) {
    val childPadding = rememberChildPadding()
    val focusMemory = rememberFocusMemory(viewModel.focusedKey)
    RestoreRememberedFocus(focusMemory)
    MusicTabColumn(onScroll = onScroll, isTopBarVisible = isTopBarVisible) {
        if (favorites.tracks.isNotEmpty()) item(key = "play-all") {
            MusicButton(
                text = stringResource(R.string.music_play_all),
                icon = Icons.Outlined.PlayArrow,
                primary = true,
                onClick = {
                    focusMemory.lastKey.value = null
                    onPlay(QueueRef.Favorites())
                },
                modifier = Modifier.padding(start = childPadding.start, top = childPadding.top),
            )
        }
        if (favorites.artists.isNotEmpty()) item(key = "artists") {
            TileRow(
                items = favorites.artists,
                key = { it.id },
                itemTitle = { it.name },
                itemImage = { artist, _, imageModifier ->
                    MusicImage(
                        url = artist.imageUri,
                        contentDescription = artist.name,
                        modifier = imageModifier.fillMaxWidth(),
                        placeholderKey = artist.id,
                        fallbackText = artist.name,
                    )
                },
                modifier = Modifier.padding(top = 16.dp),
                itemDirection = ItemDirection.Square,
                title = stringResource(R.string.music_favorite_artists),
                titleStyle = MaterialTheme.typography.titleMedium,
                onItemSelected = onArtistClick,
                focusMemory = focusMemory,
            )
        }
        if (favorites.tracks.isNotEmpty()) {
            item(key = "tracks-title") {
                Text(
                    text = stringResource(R.string.music_favorite_tracks),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        start = childPadding.start, top = 24.dp, bottom = 8.dp
                    ),
                )
            }
            itemsIndexed(favorites.tracks, key = { _, t -> "fav-${t.id}" }) { index, track ->
                MusicTrackRow(
                    track = track,
                    onClick = { onPlay(QueueRef.Favorites(index)) },
                    modifier = Modifier
                        .padding(horizontal = childPadding.start)
                        .rememberFocus(focusMemory, "fav-${track.id}"),
                )
            }
        }
    }
}

sealed interface FavoritesState {
    data object Loading : FavoritesState
    data object Error : FavoritesState
    data class Ready(val favorites: MusicFavorites) : FavoritesState
}

@HiltViewModel
class MusicFavoritesViewModel @Inject constructor(
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<FavoritesState>(FavoritesState.Loading)
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    /** The card or track that last had focus, so Back returns to it. */
    val focusedKey = mutableStateOf<String?>(null)

    /** Reloads in place, keeping what's shown while it refreshes. */
    fun load() {
        viewModelScope.launch {
            val shown = _state.value
            _state.value = try {
                FavoritesState.Ready(repository.favorites())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (shown is FavoritesState.Ready) shown else FavoritesState.Error
            }
        }
    }
}
