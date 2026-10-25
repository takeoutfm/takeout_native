package com.takeoutfm.tv.presentation.screens.music.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.takeoutfm.tv.R
import com.takeoutfm.shared.music.Album
import com.takeoutfm.shared.music.FeaturedAlbum
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.PlayedQueue
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.MusicHomeSkeleton
import com.takeoutfm.tv.presentation.common.TileRow
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.player.QueueRef
import com.takeoutfm.tv.presentation.utils.FocusMemory
import com.takeoutfm.tv.presentation.utils.RestoreRememberedFocus
import com.takeoutfm.tv.presentation.utils.rememberFocusMemory
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
    val featured by viewModel.featured.collectAsStateWithLifecycle()
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
        // The banner's slot is kept while it loads: inserting it above rows that are already
        // shown would leave the list scrolled past it (top bar hidden, focus lost).
        val banner = featured
        if (banner == null || banner.isNotEmpty()) item(key = "featured") {
            // Same height as video Home's banner, whether or not the top bar shows.
            val bannerModifier = Modifier
                .fillMaxWidth()
                .height(324.dp)
            if (banner == null) {
                Box(bannerModifier)
            } else {
                MusicFeaturedCarousel(
                    albums = banner,
                    onPlay = { onPlay(QueueRef.Album(it.album.id)) },
                    modifier = bannerModifier,
                )
            }
        }
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

    /** The banner's new albums; null while loading (empty: no banner). */
    val featured: StateFlow<List<FeaturedAlbum>?> = repository.featured()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The album cover that last had focus, so Back from an album returns to it. */
    val focusedKey = mutableStateOf<String?>(null)

    /** Null while loading. */
    val state: StateFlow<MusicHomeState?> =
        combine(repository.recentlyAdded(), repository.newReleases()) { added, new ->
            MusicHomeState(added, new)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
