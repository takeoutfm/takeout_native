package com.takeoutfm.tv.presentation.screens.music.artists

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.takeoutfm.tv.data.entities.music.Artist
import com.takeoutfm.tv.data.music.TakeoutMusicRepository
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.PosterGridSkeleton
import com.takeoutfm.tv.presentation.common.TileGrid
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Every artist A–Z, as square photos. */
@Composable
fun ArtistsScreen(
    onArtistClick: (Artist) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: ArtistsViewModel = hiltViewModel(),
) {
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val list = artists
    if (list == null) {
        PosterGridSkeleton(Modifier.fillMaxSize(), aspectRatio = 1f)
        return
    }
    TileGrid(
        items = list,
        key = { it.id },
        itemTitle = { it.name },
        itemImage = { artist, imageModifier ->
            MusicImage(
                url = artist.imageUri,
                contentDescription = artist.name,
                modifier = imageModifier,
                placeholderKey = artist.id,
                fallbackText = artist.name,
            )
        },
        onItemClick = onArtistClick,
        aspectRatio = ItemDirection.Square.aspectRatio,
        onScroll = onScroll,
        isTopBarVisible = isTopBarVisible,
        modifier = Modifier.fillMaxSize(),
    )
}

@HiltViewModel
class ArtistsViewModel @Inject constructor(
    repository: TakeoutMusicRepository,
) : ViewModel() {
    /** Null while loading. */
    val artists: StateFlow<List<Artist>?> = repository.artists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
