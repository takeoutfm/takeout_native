package com.google.jetstream.presentation.screens.music.album

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.jetstream.R
import com.google.jetstream.data.entities.music.Album
import com.google.jetstream.data.entities.music.AlbumDetails
import com.google.jetstream.data.entities.music.Track
import com.google.jetstream.data.music.TakeoutMusicRepository
import com.google.jetstream.presentation.common.DetailsSkeleton
import com.google.jetstream.presentation.common.Error
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.screens.music.common.MusicButton
import com.google.jetstream.presentation.screens.music.common.MusicImage
import com.google.jetstream.presentation.screens.music.home.AlbumRow
import com.google.jetstream.presentation.screens.music.player.QueueRef
import com.google.jetstream.presentation.theme.JetStreamButtonShape
import com.google.jetstream.presentation.theme.JetStreamCardShape
import com.google.jetstream.presentation.utils.FocusMemory
import com.google.jetstream.presentation.utils.RestoreRememberedFocus
import com.google.jetstream.presentation.utils.rememberFocus
import com.google.jetstream.presentation.utils.rememberFocusMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AlbumScreen {
    const val AlbumIdBundleKey = "albumId"
}

/** An album: cover and play buttons, its track list, then similar albums. */
@Composable
fun AlbumScreen(
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlay: (QueueRef) -> Unit,
    onBackPressed: () -> Unit,
    viewModel: AlbumScreenViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackPressed)
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        AlbumState.Loading -> DetailsSkeleton(Modifier.fillMaxSize())
        AlbumState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is AlbumState.Ready -> {
            val focusMemory = rememberFocusMemory(viewModel.focusedKey)
            RestoreRememberedFocus(focusMemory)
            AlbumContent(
                details = s.details,
                focusMemory = focusMemory,
                onArtistClick = {
                    focusMemory.lastKey.value = null
                    onArtistClick(s.details.artistId)
                },
                onAlbumClick = onAlbumClick,
                onPlay = onPlay,
            )
        }
    }
}

@Composable
private fun AlbumContent(
    details: AlbumDetails,
    focusMemory: FocusMemory,
    onArtistClick: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlay: (QueueRef) -> Unit,
) {
    val albumId = details.album.id
    val childPadding = rememberChildPadding()
    LazyColumn(
        contentPadding = PaddingValues(bottom = 108.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            AlbumHeader(
                details = details,
                onPlay = {
                    focusMemory.lastKey.value = null
                    onPlay(QueueRef.Album(albumId))
                },
                onArtistClick = onArtistClick,
            )
        }
        val multiDisc = details.discCount > 1
        itemsIndexed(details.tracks, key = { _, track -> track.id }) { index, track ->
            val firstOfDisc = index == 0 || details.tracks[index - 1].discNum != track.discNum
            Column(Modifier.padding(horizontal = childPadding.start)) {
                if (multiDisc && firstOfDisc) {
                    Text(
                        text = stringResource(R.string.music_disc, track.discNum),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 16.dp),
                    )
                }
                TrackListItem(
                    track = track,
                    showArtist = track.artist != details.album.artist,
                    onClick = { onPlay(QueueRef.Album(albumId, index)) },
                    modifier = Modifier.rememberFocus(focusMemory, track.id),
                )
            }
        }
        if (details.similar.isNotEmpty()) item(key = "similar") {
            AlbumRow(
                title = stringResource(R.string.music_similar_albums),
                albums = details.similar,
                onAlbumClick = onAlbumClick,
                focusMemory = focusMemory,
                keyPrefix = "similar-",
            )
        }
    }
}

/** One line of the track list: number, title (and artist when it differs from the album's). */
@Composable
private fun TrackListItem(
    track: Track,
    showArtist: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(JetStreamButtonShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = track.trackNum.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(48.dp),
            )
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showArtist) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .weight(0.6f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumHeader(
    details: AlbumDetails,
    onPlay: () -> Unit,
    onArtistClick: () -> Unit,
) {
    val childPadding = rememberChildPadding()
    val album = details.album
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
                url = album.coverUri,
                contentDescription = album.name,
                modifier = Modifier
                    .size(260.dp)
                    .clip(JetStreamCardShape),
                placeholderKey = album.id,
            )
            Column(Modifier.padding(start = 32.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val facts = listOfNotNull(
                    album.year.ifEmpty { null },
                    album.type.takeIf { it.isNotEmpty() && it != "Album" },
                    pluralStringResource(
                        R.plurals.music_track_count, details.tracks.size, details.tracks.size
                    ),
                )
                Text(
                    text = facts.joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    MusicButton(
                        modifier = bringHeaderIntoView,
                        text = stringResource(R.string.music_play),
                        icon = Icons.Outlined.PlayArrow,
                        primary = true,
                        onClick = onPlay,
                    )
                    MusicButton(
                        modifier = bringHeaderIntoView,
                        text = stringResource(R.string.music_artist),
                        icon = Icons.Default.Person,
                        onClick = onArtistClick,
                    )
                }
            }
        }
    }
}

sealed interface AlbumState {
    data object Loading : AlbumState
    data object Error : AlbumState
    data class Ready(val details: AlbumDetails) : AlbumState
}

@HiltViewModel
class AlbumScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    private val albumId: String = savedStateHandle[AlbumScreen.AlbumIdBundleKey] ?: ""

    private val _state = MutableStateFlow<AlbumState>(AlbumState.Loading)

    /** Loaded once per visit, so coming back keeps the page (and its scroll and focus). */
    val state: StateFlow<AlbumState> = _state.asStateFlow()

    /** The track or card that last had focus, so Back from the player returns to it. */
    val focusedKey = mutableStateOf<String?>(null)

    init {
        load()
    }

    fun load() {
        _state.value = AlbumState.Loading
        viewModelScope.launch {
            _state.value = try {
                AlbumState.Ready(repository.albumDetails(albumId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AlbumState.Error
            }
        }
    }
}
