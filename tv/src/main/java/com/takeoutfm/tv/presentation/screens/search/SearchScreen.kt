/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.takeoutfm.tv.presentation.screens.search

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.takeoutfm.tv.R
import com.takeoutfm.tv.data.entities.Movie
import com.takeoutfm.tv.data.entities.MovieList
import com.takeoutfm.tv.data.entities.SearchResults
import com.takeoutfm.shared.music.Album
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.MusicSearchResults
import com.takeoutfm.shared.music.Track
import com.takeoutfm.tv.presentation.common.TileRow
import com.takeoutfm.tv.presentation.screens.music.artist.TrackRow
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.common.TrackMenuDialog
import com.takeoutfm.tv.presentation.screens.music.home.AlbumRow
import com.takeoutfm.tv.presentation.screens.music.player.QueueRef
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.MoviesRow
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape

@Composable
fun SearchScreen(
    onMovieClick: (movie: Movie) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlay: (QueueRef) -> Unit,
    openNowPlaying: () -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    searchScreenViewModel: SearchScreenViewModel = hiltViewModel(),
) {
    val lazyColumnState = rememberLazyListState()
    val shouldShowTopBar by remember {
        derivedStateOf {
            lazyColumnState.firstVisibleItemIndex == 0 &&
                lazyColumnState.firstVisibleItemScrollOffset < 100
        }
    }

    val searchState by searchScreenViewModel.searchState.collectAsStateWithLifecycle()

    LaunchedEffect(shouldShowTopBar) {
        onScroll(shouldShowTopBar)
    }

    // Kept here, not in SearchResult, so the query survives while results load.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var lastResults by remember { mutableStateOf(SearchResults()) }
    var lastMusic by remember { mutableStateOf(MusicSearchResults()) }
    val s = searchState
    if (s is SearchState.Done) {
        lastResults = s.results
        lastMusic = s.music ?: MusicSearchResults()
    }

    SearchResult(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        results = lastResults,
        music = lastMusic,
        isSearching = s is SearchState.Searching,
        searchMovies = searchScreenViewModel::query,
        onMovieClick = onMovieClick,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onPlay = onPlay,
        onPlayTrack = { index ->
            searchScreenViewModel.playTracks(searchQuery, lastMusic.tracks, index)
            openNowPlaying()
        },
        lazyColumnState = lazyColumnState,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchResult(
    query: String,
    onQueryChange: (String) -> Unit,
    results: SearchResults,
    music: MusicSearchResults,
    isSearching: Boolean,
    searchMovies: (queryString: String) -> Unit,
    onMovieClick: (movie: Movie) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlay: (QueueRef) -> Unit,
    onPlayTrack: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    lazyColumnState: LazyListState = rememberLazyListState(),
) {
    val childPadding = rememberChildPadding()
    val searchQuery = query
    val keyboardController = LocalSoftwareKeyboardController.current
    val submit = {
        keyboardController?.hide()
        searchMovies(searchQuery)
    }
    val tfFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val tfInteractionSource = remember { MutableInteractionSource() }

    val isTfFocused by tfInteractionSource.collectIsFocusedAsState()
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    menuTrack?.let { TrackMenuDialog(it, onDismissRequest = { menuTrack = null }) }

    val voiceSearch = rememberVoiceSearch(
        onPartial = onQueryChange,
        onResult = { heard ->
            onQueryChange(heard)
            searchMovies(heard)
        },
    )

    LazyColumn(
        modifier = modifier,
        state = lazyColumnState
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = childPadding.start)
                    .padding(top = 8.dp),
            ) {
                Surface(
                    shape = ClickableSurfaceDefaults.shape(shape = JetStreamCardShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                        focusedContainerColor = MaterialTheme.colorScheme.inverseOnSurface,
                        pressedContainerColor = MaterialTheme.colorScheme.inverseOnSurface,
                        focusedContentColor = MaterialTheme.colorScheme.onSurface,
                        pressedContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(
                                width = if (isTfFocused) 2.dp else 1.dp,
                                color = animateColorAsState(
                                    targetValue = if (isTfFocused) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.border,
                                    label = ""
                                ).value
                            ),
                            shape = JetStreamCardShape
                        )
                    ),
                    tonalElevation = 2.dp,
                    modifier = Modifier.weight(1f),
                    onClick = { tfFocusRequester.requestFocus() }
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onQueryChange,
                        decorationBox = {
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 16.dp)
                                    .padding(start = 20.dp),
                            ) {
                                it()
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        modifier = Modifier.graphicsLayer { alpha = 0.6f },
                                        text = stringResource(
                                            if (voiceSearch.isListening) {
                                                R.string.search_listening
                                            } else {
                                                R.string.search_screen_et_placeholder
                                            }
                                        ),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                vertical = 4.dp,
                                horizontal = 8.dp
                            )
                            .focusRequester(tfFocusRequester)
                            .onKeyEvent {
                                // Only consume the keys handled here: swallowing everything
                                // also ate Enter (so a hardware keyboard couldn't search) and
                                // Left/Right.
                                val handled = when (it.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_DOWN,
                                    KeyEvent.KEYCODE_DPAD_UP,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    KeyEvent.KEYCODE_SEARCH -> true
                                    else -> false
                                }
                                if (handled && it.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                                    when (it.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_DOWN ->
                                            focusManager.moveFocus(FocusDirection.Down)
                                        KeyEvent.KEYCODE_DPAD_UP ->
                                            focusManager.moveFocus(FocusDirection.Up)
                                        else -> submit()
                                    }
                                }
                                handled
                            },
                        cursorBrush = Brush.verticalGradient(
                            colors = listOf(
                                LocalContentColor.current,
                                LocalContentColor.current,
                            )
                        ),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { submit() },
                            onDone = { submit() },
                            onGo = { submit() },
                        ),
                        maxLines = 1,
                        interactionSource = tfInteractionSource,
                        textStyle = MaterialTheme.typography.titleSmall.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
                if (voiceSearch.isAvailable) {
                    Spacer(Modifier.width(16.dp))
                    // Red while listening; press again to stop early.
                    IconButton(
                        onClick = voiceSearch::toggle,
                        colors = if (voiceSearch.isListening) {
                            IconButtonDefaults.colors(
                                containerColor = ListeningRed,
                                contentColor = Color.White,
                                focusedContainerColor = ListeningRed,
                                focusedContentColor = Color.White,
                            )
                        } else {
                            IconButtonDefaults.colors()
                        },
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = stringResource(R.string.search_voice)
                        )
                    }
                }
            }
        }

        if (isSearching) {
            item(key = "searching") {
                Text(
                    text = stringResource(R.string.searching),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = childPadding.start, top = childPadding.top * 2)
                )
            }
        }
        val rows = listOf(
            Triple(R.string.favorites_movies, results.movies, ItemDirection.Vertical),
            Triple(R.string.favorites_tv_shows, results.shows, ItemDirection.Vertical),
            Triple(R.string.search_episodes, results.episodes, ItemDirection.Horizontal),
        ).filter { it.second.isNotEmpty() }
        rows.forEachIndexed { index, (titleRes, list, direction) ->
            item(key = titleRes) {
                MoviesRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (index == 0) childPadding.top * 2 else 0.dp),
                    title = stringResource(titleRes),
                    titleStyle = MaterialTheme.typography.titleMedium,
                    itemDirection = direction,
                    movieList = list
                ) { selectedMovie -> onMovieClick(selectedMovie) }
            }
        }
        // Music after video, always (LIB-12): movies and shows are what the TV is mostly for.
        val top = if (rows.isEmpty()) childPadding.top * 2 else 0.dp
        if (music.artists.isNotEmpty()) item(key = "artists") {
            TileRow(
                items = music.artists,
                key = { "artist-${it.id}" },
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
                modifier = Modifier.padding(top = top),
                itemDirection = ItemDirection.Square,
                title = stringResource(R.string.music_favorite_artists),
                titleStyle = MaterialTheme.typography.titleMedium,
                onItemSelected = onArtistClick,
            )
        }
        if (music.albums.isNotEmpty()) item(key = "albums") {
            AlbumRow(
                title = stringResource(R.string.music_albums),
                albums = music.albums,
                onAlbumClick = onAlbumClick,
                keyPrefix = "album-",
                titleStyle = MaterialTheme.typography.titleMedium,
            )
        }
        if (music.tracks.isNotEmpty()) item(key = "tracks") {
            // The tracks as a queue from the one picked, as on the phone; a long press for its menu.
            TrackRow(
                title = stringResource(R.string.music_favorite_tracks),
                tracks = music.tracks,
                focusMemory = null,
                onTrackClick = onPlayTrack,
                onTrackLongPressed = { menuTrack = it },
            )
        }
        if (music.stations.isNotEmpty()) item(key = "stations") {
            TileRow(
                items = music.stations,
                key = { "station-${it.id}" },
                itemTitle = { it.name },
                itemImage = { station, _, imageModifier ->
                    MusicImage(
                        url = station.imageUri,
                        contentDescription = station.name,
                        modifier = imageModifier.fillMaxWidth(),
                        placeholderKey = station.id,
                        fallbackText = station.name,
                    )
                },
                modifier = Modifier.padding(top = 16.dp),
                itemDirection = ItemDirection.Square,
                title = stringResource(R.string.music_stations),
                titleStyle = MaterialTheme.typography.titleMedium,
                onItemSelected = { onPlay(QueueRef.Station(it.id)) },
            )
        }
    }
}

/** The mic button while listening (the usual "recording" red). */
private val ListeningRed = Color(0xFFE53935)
