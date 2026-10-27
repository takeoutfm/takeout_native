package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil.compose.AsyncImage
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.main.AlbumRoute
import com.takeoutfm.mobile.ui.main.ArtistRoute
import com.takeoutfm.mobile.ui.main.MainViewModel
import com.takeoutfm.shared.music.AlbumDetails
import com.takeoutfm.shared.music.ArtistDetails
import com.takeoutfm.shared.music.ArtistMix
import com.takeoutfm.shared.music.TakeoutMusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A page's data: loading, loaded, or failed to load. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Loaded<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

@HiltViewModel
class ArtistViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val music: TakeoutMusicRepository,
) : ViewModel() {
    val id = saved.toRoute<ArtistRoute>().id
    private val _state = MutableStateFlow<Load<ArtistDetails>>(Load.Loading)
    val state: StateFlow<Load<ArtistDetails>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                Load.Loaded(music.artistDetails(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
        }
    }

    fun setFavorite(favorite: Boolean) {
        val details = (_state.value as? Load.Loaded)?.value ?: return
        _state.value = Load.Loaded(details.copy(isFavorite = favorite))
        viewModelScope.launch {
            runCatching { music.setArtistFavorite(details.artist.arid, favorite) }
                .onFailure { _state.update { Load.Loaded(details) } }
        }
    }
}

/** An artist: background header, Shuffle / Popular / Radio, popular tracks, releases, similar. */
@Composable
fun ArtistScreen(main: MainViewModel, nav: MusicNav, viewModel: ArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by main.favoriteEtags.collectAsStateWithLifecycle()
    val downloads by main.downloads.collectAsStateWithLifecycle()
    val details = (state as? Load.Loaded)?.value
    MusicPage(
        title = details?.artist?.name.orEmpty(),
        onBack = nav.back,
        actions = {
            if (details != null && details.artist.arid.isNotEmpty()) {
                IconButton(onClick = { viewModel.setFavorite(!details.isFavorite) }) {
                    Icon(
                        if (details.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(if (details.isFavorite) R.string.unfavorite else R.string.favorite),
                    )
                }
            }
        },
    ) { padding ->
        if (details == null) {
            LoadingOrEmpty(state is Load.Loading, stringResource(R.string.cant_load), Modifier.padding(padding))
            return@MusicPage
        }
        val id = viewModel.id
        // Popular shows its first few tracks until Show all, as Radio's sections.
        var allPopular by rememberSaveable { mutableStateOf(false) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Header(details.backgroundUri.ifEmpty { details.artist.imageUri }, details.artist.name, details.artist.genre)
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MixButton(Icons.Filled.Shuffle, stringResource(R.string.shuffle)) { main.playArtist(id, ArtistMix.Shuffle) }
                    MixButton(Icons.Filled.TrendingUp, stringResource(R.string.popular)) { main.playArtist(id, ArtistMix.Popular) }
                    MixButton(Icons.Filled.Radio, stringResource(R.string.radio)) { main.playArtist(id, ArtistMix.Radio) }
                }
            }
            if (details.popular.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.popular_tracks)) }
                val popular = details.popular.take(PopularMax)
                itemsIndexed(if (allPopular) popular else popular.take(PopularCollapsed), key = { _, t -> "p-${t.id}" }) { index, track ->
                    TrackRow(
                        track = track,
                        favorite = track.etag in favorites,
                        downloaded = downloads[track.etag]?.status,
                        onClick = { main.playArtist(id, ArtistMix.Popular, index) },
                        onFavorite = { main.setTrackFavorite(track.etag, it) },
                        onEnqueue = { last -> main.enqueueTrack(track.id, last) },
                        onAddToPlaylist = { main.addToPlaylist(com.takeoutfm.mobile.ui.main.AddTarget.TrackTarget(track.id)) },
                        showAlbum = true,
                    )
                }
                if (popular.size > PopularCollapsed) {
                    item(key = "popular-more") {
                        TextButton(onClick = { allPopular = !allPopular }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(
                                if (allPopular) stringResource(R.string.show_less)
                                else stringResource(R.string.show_all, popular.size),
                            )
                        }
                    }
                }
            }
            item {
                AlbumShelf(stringResource(R.string.albums), details.albums) { nav.openAlbum(it.id) }
                AlbumShelf(stringResource(R.string.singles), details.singles) { nav.openAlbum(it.id) }
                AlbumShelf(stringResource(R.string.other_releases), details.otherReleases) { nav.openAlbum(it.id) }
                ArtistShelf(stringResource(R.string.similar_artists), details.similar) { nav.openArtist(it.id) }
                Spacer(Modifier.height(96.dp)) // clear of the play button
            }
        }
    }
}

private const val PopularMax = 10
private const val PopularCollapsed = 3

/** The tallest a page header (artist or video backdrop) gets. */
val HeaderMaxHeight = 360.dp

/** Full width, 16:9 on phones but no taller than [HeaderMaxHeight] (tablets, landscape); clipped. */
fun Modifier.headerSize(): Modifier = this
    .fillMaxWidth()
    .layout { measurable, constraints ->
        val width = constraints.maxWidth
        val height = minOf(width * 9 / 16, HeaderMaxHeight.roundToPx())
        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }
    .clipToBounds()

/** The artist's background image with the name over a dark fade. */
@Composable
private fun Header(imageUri: String, title: String, subtitle: String) {
    Box(
        Modifier
            .headerSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (imageUri.isNotEmpty()) {
            AsyncImage(imageUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun RowScope.MixButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    // An equal share of the row each, one line: on a narrow phone the default padding left
    // too little room and the label wrapped a letter per line.
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.weight(1f),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
    }
}

@HiltViewModel
class AlbumViewModel @Inject constructor(
    saved: SavedStateHandle,
    music: TakeoutMusicRepository,
) : ViewModel() {
    val id = saved.toRoute<AlbumRoute>().id
    private val _state = MutableStateFlow<Load<AlbumDetails>>(Load.Loading)
    val state: StateFlow<Load<AlbumDetails>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                Load.Loaded(music.albumDetails(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
        }
    }
}

/** A release: cover, Play / Shuffle, tracks by disc, similar releases. */
@Composable
fun AlbumScreen(main: MainViewModel, nav: MusicNav, viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by main.favoriteEtags.collectAsStateWithLifecycle()
    val downloads by main.downloads.collectAsStateWithLifecycle()
    val details = (state as? Load.Loaded)?.value
    MusicPage(title = details?.album?.name.orEmpty(), onBack = nav.back) { padding ->
        if (details == null) {
            LoadingOrEmpty(state is Load.Loading, stringResource(R.string.cant_load), Modifier.padding(padding))
            return@MusicPage
        }
        val album = details.album
        val id = viewModel.id
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                // Large fonts: the title goes under the cover, where it has the full width.
                val stacked = LocalDensity.current.fontScale > 1.3f
                val header = @Composable { content: @Composable () -> Unit ->
                    if (stacked) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
                    } else {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                    }
                }
                header {
                    Art(album.coverUri, album.name, Modifier.size(160.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Whole words only, even when large fonts leave little room beside the cover.
                        Text(
                            album.name,
                            style = MaterialTheme.typography.titleLarge.copy(hyphens = Hyphens.None, lineBreak = LineBreak.Heading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            album.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = details.artistId.isNotEmpty()) { nav.openArtist(details.artistId) },
                        )
                        if (album.year.isNotEmpty()) Text(album.year, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { main.playAlbum(id) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.play_all), modifier = Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = { main.playAlbum(id, shuffle = true) }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.shuffle), modifier = Modifier.padding(start = 6.dp))
                    }
                    val allDownloaded = details.tracks.isNotEmpty() &&
                        details.tracks.all { downloads[it.etag]?.status == com.takeoutfm.media.offline.CacheStatus.Cached }
                    IconButton(onClick = { main.addToPlaylist(com.takeoutfm.mobile.ui.main.AddTarget.AlbumTarget(id)) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = stringResource(R.string.add_to_playlist))
                    }
                    IconButton(onClick = { main.downloadAlbum(id) }, enabled = !allDownloaded) {
                        Icon(
                            if (allDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                            contentDescription = stringResource(if (allDownloaded) R.string.downloaded else R.string.download),
                        )
                    }
                }
            }
            var disc = -1
            details.tracks.forEachIndexed { index, track ->
                if (details.discCount > 1 && track.discNum != disc) {
                    disc = track.discNum
                    item(key = "disc-$disc") { SectionTitle(stringResource(R.string.disc, track.discNum)) }
                }
                item(key = track.id) {
                    TrackRow(
                        track = track,
                        favorite = track.etag in favorites,
                        downloaded = downloads[track.etag]?.status,
                        onClick = { main.playAlbum(id, index) },
                        onFavorite = { main.setTrackFavorite(track.etag, it) },
                        onEnqueue = { last -> main.enqueueTrack(track.id, last) },
                        onAddToPlaylist = { main.addToPlaylist(com.takeoutfm.mobile.ui.main.AddTarget.TrackTarget(track.id)) },
                        number = track.trackNum,
                    )
                }
            }
            item {
                AlbumShelf(stringResource(R.string.similar_releases), details.similar) { nav.openAlbum(it.id) }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}
