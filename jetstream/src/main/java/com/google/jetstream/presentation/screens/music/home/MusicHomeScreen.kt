package com.google.jetstream.presentation.screens.music.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.jetstream.R
import com.google.jetstream.data.entities.music.Album
import com.google.jetstream.data.music.MusicHistory
import com.google.jetstream.data.music.PlayedQueue
import com.google.jetstream.data.music.TakeoutMusicRepository
import com.google.jetstream.presentation.common.ItemDirection
import com.google.jetstream.presentation.common.MusicHomeSkeleton
import com.google.jetstream.presentation.common.TileRow
import com.google.jetstream.presentation.screens.music.common.MusicImage
import com.google.jetstream.presentation.screens.music.player.QueueRef
import com.google.jetstream.presentation.utils.FocusMemory
import com.google.jetstream.presentation.utils.RestoreRememberedFocus
import com.google.jetstream.presentation.utils.rememberFocusMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Music mode's first tab: rows of recently added and newly released albums. */
@Composable
fun MusicHomeScreen(
    onAlbumClick: (Album) -> Unit,
    onPlay: (QueueRef) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: MusicHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    // Only what the row shows: playing updates each entry's position every few seconds, and a
    // list that compares different would make the row re-animate and drop focus from the card.
    val playedTiles = remember(recentlyPlayed) {
        recentlyPlayed.map { PlayedTile(it.id, it.title, it.coverUri) }
    }
    val s = state
    if (s == null) {
        MusicHomeSkeleton(Modifier.fillMaxSize())
        return
    }

    // Back from an album returns focus to its cover (kept in the ViewModel across the visit).
    val focusMemory = rememberFocusMemory(viewModel.focusedKey)
    RestoreRememberedFocus(focusMemory)

    val listState = rememberLazyListState()
    val shouldShowTopBar by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 300
        }
    }
    LaunchedEffect(shouldShowTopBar) { onScroll(shouldShowTopBar) }
    LaunchedEffect(isTopBarVisible) {
        if (isTopBarVisible) listState.animateScrollToItem(0)
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 108.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (playedTiles.isNotEmpty()) item(key = "played") {
            // What was played on this device, newest first; selecting resumes where it was left.
            TileRow(
                items = playedTiles,
                key = { "played-${it.id}" },
                itemTitle = { it.title },
                itemImage = { played, _, imageModifier ->
                    MusicImage(
                        url = played.coverUri,
                        contentDescription = played.title,
                        modifier = imageModifier.fillMaxWidth(),
                        placeholderKey = played.id,
                        fallbackText = played.title,
                    )
                },
                modifier = Modifier.padding(top = 16.dp),
                itemDirection = ItemDirection.Square,
                title = stringResource(R.string.music_recently_played),
                onItemSelected = { onPlay(QueueRef.History(it.id)) },
                focusMemory = focusMemory,
            )
        }
        if (s.recentlyAdded.isNotEmpty()) item(key = "added") {
            AlbumRow(
                title = stringResource(R.string.music_recently_added),
                albums = s.recentlyAdded,
                onAlbumClick = onAlbumClick,
                focusMemory = focusMemory,
                keyPrefix = "added-",
            )
        }
        if (s.newReleases.isNotEmpty()) item(key = "new") {
            AlbumRow(
                title = stringResource(R.string.music_new_releases),
                albums = s.newReleases,
                onAlbumClick = onAlbumClick,
                focusMemory = focusMemory,
                keyPrefix = "new-",
            )
        }
    }
}

/**
 * A titled row of square album covers; the focused one shows its name. [keyPrefix] keeps
 * [focusMemory] keys apart when the same album is in more than one row.
 */
@Composable
fun AlbumRow(
    title: String,
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
    focusMemory: FocusMemory? = null,
    keyPrefix: String = "",
) {
    TileRow(
        items = albums,
        key = { keyPrefix + it.id },
        itemTitle = { it.name },
        itemImage = { album, _, imageModifier ->
            MusicImage(
                url = album.coverUri,
                contentDescription = album.name,
                modifier = imageModifier.fillMaxWidth(),
                placeholderKey = album.id,
                fallbackText = album.name,
            )
        },
        modifier = modifier.padding(top = 16.dp),
        itemDirection = ItemDirection.Square,
        title = title,
        onItemSelected = onAlbumClick,
        focusMemory = focusMemory,
    )
}

/** A Recently Played card. */
private data class PlayedTile(val id: String, val title: String, val coverUri: String)

data class MusicHomeState(
    val recentlyAdded: List<Album>,
    val newReleases: List<Album>,
)

@HiltViewModel
class MusicHomeViewModel @Inject constructor(
    repository: TakeoutMusicRepository,
    history: MusicHistory,
) : ViewModel() {
    /** Queues played on this device, newest first. */
    val recentlyPlayed: StateFlow<List<PlayedQueue>> = history.entries

    /** The album cover that last had focus, so Back from an album returns to it. */
    val focusedKey = mutableStateOf<String?>(null)

    /** Null while loading. */
    val state: StateFlow<MusicHomeState?> =
        combine(repository.recentlyAdded(), repository.newReleases()) { added, new ->
            MusicHomeState(added, new)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
