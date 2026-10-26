package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.main.MainViewModel
import com.takeoutfm.mobile.ui.main.QueueRoute
import com.takeoutfm.shared.music.MusicPlaylist
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.MusicSearchResults
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.util.listFlow
import com.takeoutfm.shared.video.TakeoutVideoRepository
import com.takeoutfm.shared.video.VideoCard
import com.takeoutfm.shared.video.VideoSearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchResults(
    val query: String = "",
    val music: MusicSearchResults = MusicSearchResults(),
    val video: VideoSearchResults = VideoSearchResults(),
    val searching: Boolean = false,
) {
    val isEmpty: Boolean
        get() = music.isEmpty && video.movies.isEmpty() && video.shows.isEmpty() && video.episodes.isEmpty()
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val music: TakeoutMusicRepository,
    private val video: TakeoutVideoRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    /** Searches as you type (after a pause): music as typed, video requiring every word. */
    val results: StateFlow<SearchResults> = _query
        .debounce(400)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) return@flow emit(SearchResults())
                emit(SearchResults(q, searching = true))
                emit(
                    coroutineScope {
                        val m = async { runCatching { music.search(q) }.getOrDefault(MusicSearchResults()) }
                        val v = async { runCatching { video.search(q) }.getOrDefault(VideoSearchResults()) }
                        SearchResults(q, m.await(), v.await())
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())
}

/** Search across the library; the query syntax is the server's ("artist:name", "guitar:name"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(main: MainViewModel, nav: MusicNav, videoNav: com.takeoutfm.mobile.ui.video.VideoNav, viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val favorites by main.favoriteEtags.collectAsStateWithLifecycle()
    val downloads by main.downloads.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = nav.back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = null) }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        if (results.query.isEmpty() || results.isEmpty) {
            if (results.query.isNotEmpty()) {
                LoadingOrEmpty(results.searching, stringResource(R.string.no_results), Modifier.padding(padding))
            }
            return@Scaffold
        }
        val m = results.music
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { ArtistShelf(stringResource(R.string.search_artists), m.artists) { nav.openArtist(it.id) } }
            item { AlbumShelf(stringResource(R.string.search_releases), m.albums) { nav.openAlbum(it.id) } }
            if (m.tracks.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.search_tracks)) }
                items(m.tracks, key = { "t-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        favorite = track.etag in favorites,
                        downloaded = downloads[track.etag]?.status,
                        onClick = { main.playTrack(track.id) },
                        onFavorite = { main.setTrackFavorite(track.etag, it) },
                        onEnqueue = { last -> main.enqueueTrack(track.id, last) },
                        showAlbum = true,
                    )
                }
            }
            if (m.stations.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.search_stations)) }
                items(m.stations, key = { "s-${it.id}" }) { station ->
                    ListItem(
                        headlineContent = { Text(station.name) },
                        leadingContent = { Art(station.imageUri, station.name, Modifier.size(48.dp), RoundedCornerShape(6.dp)) },
                        modifier = Modifier.clickable { main.playStation(station.id) },
                    )
                }
            }
            item { VideoShelf(stringResource(R.string.search_movies), results.video.movies) { videoNav.openVideo(it.id) } }
            item { VideoShelf(stringResource(R.string.search_shows), results.video.shows) { videoNav.openVideo(it.id) } }
            item { VideoShelf(stringResource(R.string.search_episodes), results.video.episodes) { videoNav.openVideo(it.id) } }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

/** Movies, shows or episodes in search results. */
@Composable
private fun VideoShelf(title: String, cards: List<VideoCard>, onClick: (VideoCard) -> Unit) {
    if (cards.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards, key = { it.id }) { card ->
            Column(Modifier.width(112.dp).clickable { onClick(card) }) {
                Art(card.posterUri, card.title, Modifier.width(112.dp).height(168.dp))
                Text(card.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@HiltViewModel
class PlaylistsViewModel @Inject constructor(music: TakeoutMusicRepository) : ViewModel() {
    val playlists: Flow<List<MusicPlaylist>> = listFlow { music.playlists() }
}

/** The user's playlists; one opens its tracks. */
@Composable
fun PlaylistsScreen(nav: MusicNav, viewModel: PlaylistsViewModel = hiltViewModel()) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = null)
    MusicPage(title = stringResource(R.string.playlists), onBack = nav.back) { padding ->
        val list = playlists
        if (list.isNullOrEmpty()) {
            LoadingOrEmpty(list == null, stringResource(R.string.nothing_here), Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(list, key = { it.id }) { p ->
                    ListItem(
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(stringResource(R.string.track_count, p.trackCount)) },
                        modifier = Modifier.clickable { nav.openQueue(QueueKind.Playlist, p.id, p.name) },
                    )
                }
            }
        }
    }
}

/** What a queue page shows. */
object QueueKind {
    const val Playlist = "playlist"
    const val Favorites = "favorites"
}

@HiltViewModel
class QueueViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val music: TakeoutMusicRepository,
) : ViewModel() {
    val route = saved.toRoute<QueueRoute>()
    private val _state = MutableStateFlow<Load<MusicQueue>>(Load.Loading)
    val state: StateFlow<Load<MusicQueue>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                Load.Loaded(
                    if (route.kind == QueueKind.Favorites) music.favoritesQueue() else music.playlistQueue(route.id)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
        }
    }
}

/** A playlist's (or the favorite tracks') items; tapping one plays from there. */
@Composable
fun QueueScreen(main: MainViewModel, nav: MusicNav, viewModel: QueueViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by main.downloads.collectAsStateWithLifecycle()
    val route = viewModel.route
    val play = { index: Int ->
        if (route.kind == QueueKind.Favorites) main.playFavorites(index) else main.playPlaylist(route.id, index)
    }
    MusicPage(title = route.title, onBack = nav.back) { padding ->
        val queue = (state as? Load.Loaded)?.value
        if (queue == null || queue.items.isEmpty()) {
            LoadingOrEmpty(state is Load.Loading, stringResource(if (state is Load.Failed) R.string.cant_load else R.string.nothing_here), Modifier.padding(padding))
            return@MusicPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Button(onClick = { play(0) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.play_all), modifier = Modifier.padding(start = 6.dp))
                    }
                    IconButton(onClick = { if (route.kind == QueueKind.Favorites) main.downloadFavorites() else main.downloadPlaylist(route.id) }) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Download, contentDescription = stringResource(R.string.download))
                    }
                }
            }
            itemsIndexed(queue.items) { index, item ->
                ListItem(
                    leadingContent = { Art(item.imageUri, item.album, Modifier.size(48.dp), RoundedCornerShape(6.dp)) },
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOf(item.artist, item.album).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { DownloadBadge(downloads[item.etag]?.status) },
                    modifier = Modifier.clickable { play(index) },
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}
