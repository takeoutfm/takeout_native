package com.takeoutfm.tv.presentation.screens.music.artist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.takeoutfm.tv.R
import com.takeoutfm.shared.music.Album
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.ArtistDetails
import com.takeoutfm.shared.music.Track
import com.takeoutfm.shared.music.ArtistMix
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.tv.presentation.common.DetailsSkeleton
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.TileRow
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.screens.music.common.MusicButton
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.player.QueueRef
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import com.takeoutfm.tv.presentation.utils.FocusMemory
import com.takeoutfm.tv.presentation.utils.RestoreRememberedFocus
import com.takeoutfm.tv.presentation.utils.rememberFocusMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ArtistScreen {
    const val ArtistIdBundleKey = "artistId"
}

/** An artist: photo over their background, play buttons, then their music in rows. */
@Composable
fun ArtistScreen(
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlay: (QueueRef) -> Unit,
    onBackPressed: () -> Unit,
    viewModel: ArtistScreenViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackPressed)
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        ArtistState.Loading -> DetailsSkeleton(Modifier.fillMaxSize())
        ArtistState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is ArtistState.Ready -> {
            val focusMemory = rememberFocusMemory(viewModel.focusedCardKey)
            RestoreRememberedFocus(focusMemory)
            val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
            ArtistContent(
                details = s.details,
                isFavorite = isFavorite,
                onToggleFavorite = viewModel::toggleFavorite,
                focusMemory = focusMemory,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlay = { ref ->
                    // Back from the player then lands on the buttons, not an earlier card.
                    focusMemory.lastKey.value = null
                    onPlay(ref)
                },
            )
        }
    }
}

@Composable
private fun ArtistContent(
    details: ArtistDetails,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    focusMemory: FocusMemory,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlay: (QueueRef) -> Unit,
) {
    val artistId = details.artist.id
    LazyColumn(
        contentPadding = PaddingValues(bottom = 108.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            ArtistHeader(
                details = details,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onPlay = { mix -> onPlay(QueueRef.Artist(artistId, mix)) },
            )
        }
        if (details.popular.isNotEmpty()) item(key = "popular") {
            TrackRow(
                title = stringResource(R.string.music_popular),
                tracks = details.popular,
                focusMemory = focusMemory,
                onTrackClick = { index ->
                    focusMemory.lastKey.value = null
                    onPlay(QueueRef.Artist(artistId, ArtistMix.Popular, index))
                },
            )
        }
        albumRow(R.string.music_albums, details.albums, focusMemory, onAlbumClick)
        albumRow(R.string.music_singles, details.singles, focusMemory, onAlbumClick)
        albumRow(R.string.music_other_releases, details.otherReleases, focusMemory, onAlbumClick)
        if (details.similar.isNotEmpty()) item(key = "similar") {
            TileRow(
                items = details.similar,
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
                title = stringResource(R.string.music_similar_artists),
                titleStyle = MaterialTheme.typography.titleMedium,
                onItemSelected = onArtistClick,
                focusMemory = focusMemory,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumRow(
    titleRes: Int,
    albums: List<Album>,
    focusMemory: FocusMemory,
    onAlbumClick: (Album) -> Unit,
) {
    if (albums.isEmpty()) return
    item(key = "albums-$titleRes") {
        TileRow(
            items = albums,
            key = { it.id },
            itemTitle = { if (it.year.isEmpty()) it.name else "${it.name} · ${it.year}" },
            itemImage = { album, _, imageModifier ->
                MusicImage(
                    url = album.coverUri,
                    contentDescription = album.name,
                    modifier = imageModifier.fillMaxWidth(),
                    placeholderKey = album.id,
                    fallbackText = album.name,
                )
            },
            modifier = Modifier.padding(top = 16.dp),
            itemDirection = ItemDirection.Square,
            title = stringResource(titleRes),
            titleStyle = MaterialTheme.typography.titleMedium,
            onItemSelected = onAlbumClick,
            focusMemory = focusMemory,
        )
    }
}

/** Tracks as cover cards (the track title under the focused one); selecting plays from it. */
@Composable
fun TrackRow(
    title: String,
    tracks: List<Track>,
    focusMemory: FocusMemory?,
    onTrackClick: (index: Int) -> Unit,
) {
    TileRow(
        items = tracks,
        key = { it.id },
        itemTitle = { it.title },
        itemImage = { track, _, imageModifier ->
            MusicImage(
                url = track.coverUri,
                contentDescription = track.title,
                modifier = imageModifier.fillMaxWidth(),
                placeholderKey = track.id,
            )
        },
        modifier = Modifier.padding(top = 16.dp),
        itemDirection = ItemDirection.Square,
        title = title,
        titleStyle = MaterialTheme.typography.titleMedium,
        onItemSelected = { track -> onTrackClick(tracks.indexOf(track)) },
        focusMemory = focusMemory,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistHeader(
    details: ArtistDetails,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: (ArtistMix) -> Unit,
) {
    val childPadding = rememberChildPadding()
    // Focusing a button scrolls the whole header into view, not just the button.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bringHeaderIntoView = Modifier.onFocusChanged {
        if (it.isFocused) scope.launch { bringIntoView.bringIntoView() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(400.dp)
            .bringIntoViewRequester(bringIntoView)
    ) {
        if (details.backgroundUri.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .crossfade(true)
                    .data(details.backgroundUri)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Darken towards the left and bottom so the text stays readable over any photo.
        val background = MaterialTheme.colorScheme.background
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to background, 0.6f to background.copy(alpha = 0.6f),
                        1f to Color.Transparent
                    )
                )
                .background(
                    Brush.verticalGradient(0.6f to Color.Transparent, 1f to background)
                )
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = childPadding.start),
        ) {
            MusicImage(
                url = details.artist.imageUri,
                contentDescription = details.artist.name,
                modifier = Modifier
                    .size(220.dp)
                    .clip(JetStreamCardShape),
                placeholderKey = details.artist.id,
            )
            Column(Modifier.padding(start = 32.dp)) {
                Text(
                    text = details.artist.name,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (details.artist.genre.isNotEmpty()) {
                    Text(
                        text = details.artist.genre.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    MusicButton(
                        modifier = bringHeaderIntoView,
                        text = stringResource(R.string.music_shuffle),
                        icon = Icons.Default.Shuffle,
                        primary = true,
                        onClick = { onPlay(ArtistMix.Shuffle) },
                    )
                    if (details.popular.isNotEmpty()) {
                        MusicButton(
                            modifier = bringHeaderIntoView,
                            text = stringResource(R.string.music_popular),
                            icon = Icons.Default.TrendingUp,
                            onClick = { onPlay(ArtistMix.Popular) },
                        )
                    }
                    MusicButton(
                        modifier = bringHeaderIntoView,
                        text = stringResource(R.string.music_radio),
                        icon = Icons.Default.Radio,
                        onClick = { onPlay(ArtistMix.Radio) },
                    )
                    if (details.artist.arid.isNotEmpty()) {
                        MusicButton(
                            modifier = bringHeaderIntoView,
                            text = stringResource(
                                if (isFavorite) R.string.music_favorite
                                else R.string.music_add_favorite
                            ),
                            icon = if (isFavorite) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Outlined.FavoriteBorder
                            },
                            onClick = onToggleFavorite,
                        )
                    }
                }
            }
        }
    }
}

sealed interface ArtistState {
    data object Loading : ArtistState
    data object Error : ArtistState
    data class Ready(val details: ArtistDetails) : ArtistState
}

@HiltViewModel
class ArtistScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    private val artistId: String = savedStateHandle[ArtistScreen.ArtistIdBundleKey] ?: ""

    private val _state = MutableStateFlow<ArtistState>(ArtistState.Loading)

    /** Loaded once per visit, so coming back keeps the page (and its scroll and focus). */
    val state: StateFlow<ArtistState> = _state.asStateFlow()

    /** The card that last had focus, so Back from an album returns to it. */
    val focusedCardKey = mutableStateOf<String?>(null)

    private val _isFavorite = MutableStateFlow(false)

    /** Flipped right away on toggle, reverted if the server fails. */
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    fun toggleFavorite() {
        val details = (_state.value as? ArtistState.Ready)?.details ?: return
        val favorite = !_isFavorite.value
        _isFavorite.value = favorite
        viewModelScope.launch {
            try {
                repository.setArtistFavorite(details.artist.arid, favorite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isFavorite.value = !favorite
            }
        }
    }

    init {
        load()
    }

    fun load() {
        _state.value = ArtistState.Loading
        viewModelScope.launch {
            _state.value = try {
                repository.artistDetails(artistId)
                    .also { _isFavorite.value = it.isFavorite }
                    .let { ArtistState.Ready(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArtistState.Error
            }
        }
    }
}
