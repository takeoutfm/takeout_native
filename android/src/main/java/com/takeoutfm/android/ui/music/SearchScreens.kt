package com.takeoutfm.android.ui.music

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.produceState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.res.pluralStringResource
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
import com.takeoutfm.android.R
import com.takeoutfm.android.ui.common.Art
import com.takeoutfm.android.ui.common.LoadingOrEmpty
import com.takeoutfm.android.ui.main.MainViewModel
import com.takeoutfm.android.ui.main.QueueRoute
import com.takeoutfm.shared.music.MusicPlaylist
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.MusicSearchResults
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.video.TakeoutVideoRepository
import com.takeoutfm.shared.video.VideoCard
import com.takeoutfm.shared.video.VideoSearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
fun SearchScreen(main: MainViewModel, nav: MusicNav, videoNav: com.takeoutfm.android.ui.video.VideoNav, viewModel: SearchViewModel = hiltViewModel()) {
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
                        onAddToPlaylist = { main.addToPlaylist(com.takeoutfm.android.ui.main.AddTarget.TrackTarget(track.id)) },
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
class PlaylistsViewModel @Inject constructor(private val music: TakeoutMusicRepository) : ViewModel() {
    private val loadFailed = MutableStateFlow(false)

    /**
     * The core's playlists (decision 017): creating, deleting or adding to one anywhere in the
     * app shows here at once. Empty if loading failed with none known yet.
     */
    val playlists: StateFlow<List<MusicPlaylist>?> =
        combine(music.currentPlaylists, loadFailed) { list, failed -> list ?: if (failed) emptyList() else null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, music.currentPlaylists.value)

    /** Loads them again when shown, for changes made elsewhere. */
    fun load() {
        viewModelScope.launch { loadFailed.value = runCatching { music.playlists() }.isFailure }
    }

    /** [playlist]'s covers for its mosaic; none if they can't be loaded. */
    suspend fun covers(playlist: MusicPlaylist): List<String> = try {
        music.playlistCovers(playlist.id, playlist.trackCount)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Favorite Tracks, then the user's playlists A–Z, as a grid of covers (as on the Mac): each
 * playlist's art is a mosaic of its first four albums' covers. + creates one; a card's ⋮ deletes it.
 */
@Composable
fun PlaylistsScreen(main: MainViewModel, nav: MusicNav, viewModel: PlaylistsViewModel = hiltViewModel()) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MusicPlaylist?>(null) }
    MusicPage(
        title = stringResource(R.string.playlists),
        onBack = nav.back,
        actions = {
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_playlist)) }
        },
    ) { padding ->
        val list = playlists
        if (list == null) {
            LoadingOrEmpty(true, stringResource(R.string.nothing_here), Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "favorites") {
                    val title = stringResource(R.string.favorite_tracks)
                    PlaylistCard(
                        title = title,
                        subtitle = stringResource(R.string.favorite_tracks_subtitle),
                        onClick = { nav.openQueue(QueueKind.Favorites, "", title) },
                    ) { FavoriteTracksArt(it) }
                }
                items(list, key = { it.id }) { p ->
                    // Kept in the core until its track count changes, so revisits don't refetch.
                    val covers by produceState<List<String>>(emptyList(), p.id, p.trackCount) { value = viewModel.covers(p) }
                    PlaylistCard(
                        title = p.name,
                        subtitle = pluralStringResource(R.plurals.track_count, p.trackCount, p.trackCount),
                        onClick = { nav.openQueue(QueueKind.Playlist, p.id, p.name) },
                        onDelete = { deleting = p },
                    ) { PlaylistMosaic(covers, it) }
                }
            }
        }
    }
    if (creating) {
        NameDialog(stringResource(R.string.new_playlist), onConfirm = { main.createPlaylist(it); creating = false }, onDismiss = { creating = false })
    }
    deleting?.let { p ->
        DeletePlaylistDialog(p.name, onDelete = { main.deletePlaylist(p.id); deleting = null }, onDismiss = { deleting = null })
    }
}

/** A card in the Playlists grid: square art, then the name and a line under it; ⋮ when it can be deleted. */
@Composable
private fun PlaylistCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    art: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        art(Modifier.fillMaxWidth().aspectRatio(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (onDelete != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_playlist)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
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
        reload()
        // Its track count changing in the core's playlists (decision 017): an edit made here
        // (removing a track) or anywhere else in the app, so its tracks load again.
        if (route.kind != QueueKind.Favorites) viewModelScope.launch {
            music.currentPlaylists
                .map { list -> list?.firstOrNull { it.id == route.id }?.trackCount }
                .filterNotNull()
                .distinctUntilChanged()
                .drop(1)
                .collect { reload() }
        }
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = try {
                Load.Loaded(
                    if (route.kind == QueueKind.Favorites) {
                        music.favoritesQueue()
                    } else {
                        // its covers as the Playlists grid shows them, now that they're known
                        music.playlistQueue(route.id).also { music.keepCovers(route.id, it) }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
        }
    }
}

/**
 * A playlist's (or the favorite tracks') page, as an album's (as on the Mac): its mosaic beside
 * the name and track count, Play / Shuffle / Download, then the tracks (tapping one plays from
 * there). A playlist's ⋮ deletes it.
 */
@Composable
fun QueueScreen(main: MainViewModel, nav: MusicNav, viewModel: QueueViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by main.downloads.collectAsStateWithLifecycle()
    val route = viewModel.route
    val favorites = route.kind == QueueKind.Favorites
    val play = { index: Int, shuffle: Boolean ->
        if (favorites) main.playFavorites(index, shuffle) else main.playPlaylist(route.id, index, shuffle)
    }
    var deleting by remember { mutableStateOf(false) }
    MusicPage(
        title = route.title,
        onBack = nav.back,
        actions = {
            if (!favorites) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_playlist)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; deleting = true },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val queue = (state as? Load.Loaded)?.value
        if (queue == null) {
            LoadingOrEmpty(state is Load.Loading, stringResource(R.string.cant_load), Modifier.padding(padding))
            return@MusicPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val art = Modifier.size(160.dp)
                    if (favorites) FavoriteTracksArt(art) else PlaylistMosaic(TakeoutMusicRepository.coversOf(queue), art)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            route.title,
                            style = MaterialTheme.typography.titleLarge.copy(hyphens = Hyphens.None, lineBreak = LineBreak.Heading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            pluralStringResource(R.plurals.track_count, queue.items.size, queue.items.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val playable = queue.items.isNotEmpty()
                    Button(onClick = { play(0, false) }, enabled = playable) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.play_all), modifier = Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = { play(0, true) }, enabled = playable) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.shuffle), modifier = Modifier.padding(start = 6.dp))
                    }
                    val allDownloaded = playable &&
                        queue.items.all { downloads[it.etag]?.status == com.takeoutfm.media.offline.CacheStatus.Cached }
                    IconButton(
                        onClick = { if (favorites) main.downloadFavorites() else main.downloadPlaylist(route.id) },
                        enabled = playable && !allDownloaded,
                    ) {
                        Icon(
                            if (allDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                            contentDescription = stringResource(if (allDownloaded) R.string.downloaded else R.string.download),
                        )
                    }
                }
            }
            itemsIndexed(queue.items) { index, item ->
                ListItem(
                    leadingContent = { Art(item.imageUri, item.album, Modifier.size(48.dp), RoundedCornerShape(6.dp)) },
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOf(item.artist, item.album).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            DownloadBadge(downloads[item.etag]?.status)
                            if (route.kind == QueueKind.Playlist) {
                                IconButton(onClick = { main.removeFromPlaylist(route.id, index) }) {
                                    Icon(Icons.Filled.RemoveCircleOutline, contentDescription = stringResource(R.string.remove_from_playlist))
                                }
                            }
                        }
                    },
                    modifier = Modifier.clickable { play(index, false) },
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
    if (deleting) {
        DeletePlaylistDialog(
            route.title,
            onDelete = { deleting = false; main.deletePlaylist(route.id); nav.back() },
            onDismiss = { deleting = false },
        )
    }
}
