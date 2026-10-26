package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.common.Tile
import com.takeoutfm.mobile.ui.common.TileGrid
import com.takeoutfm.mobile.ui.main.ArtistType
import com.takeoutfm.mobile.ui.main.MainViewModel
import com.takeoutfm.shared.music.StationKind
import kotlinx.coroutines.launch

/** A tab page: a collapsing-on-scroll title (and subtitle) over [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabPage(
    title: String,
    subtitle: String? = null,
    content: @Composable (PaddingValues, onComingSoon: () -> Unit) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoon = stringResource(R.string.coming_soon)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Column {
                        Text(title)
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding -> content(padding) { scope.launch { snackbar.showSnackbar(comingSoon) } } }
}

/** Artists A–Z, or favorite artists; re-tapping the tab switches. */
@Composable
fun ArtistsScreen(main: MainViewModel, viewModel: MusicTabsViewModel = hiltViewModel()) {
    val type by main.artistType.collectAsStateWithLifecycle()
    val artistsFlow = remember(type) { viewModel.artists(type) }
    val artists by artistsFlow.collectAsStateWithLifecycle(initialValue = null)
    TabPage(
        title = stringResource(R.string.nav_artists),
        subtitle = stringResource(if (type == ArtistType.All) R.string.artists_all else R.string.artists_favorite),
    ) { padding, comingSoon ->
        val list = artists
        if (list.isNullOrEmpty()) {
            LoadingOrEmpty(list == null, stringResource(R.string.nothing_here), Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(list, key = { it.id }) { artist ->
                    ListItem(
                        headlineContent = { Text(artist.name) },
                        supportingContent = artist.genre.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                        leadingContent = {
                            Art(artist.imageUri, artist.name.take(1), Modifier.size(48.dp), RoundedCornerShape(50))
                        },
                        modifier = Modifier.clickable(onClick = comingSoon),
                    )
                }
            }
        }
    }
}

/** Queues played on this device, newest first. */
@Composable
fun RecentScreen(viewModel: MusicTabsViewModel = hiltViewModel()) {
    val played by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    TabPage(title = stringResource(R.string.nav_history)) { padding, comingSoon ->
        if (played.isEmpty()) {
            LoadingOrEmpty(false, stringResource(R.string.no_recent), Modifier.padding(padding))
        } else {
            TileGrid(
                played.map { Tile(it.id, it.title, it.creator, it.coverUri) },
                onClick = { comingSoon() },
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = 12.dp,
                ),
            )
        }
    }
}

/** Radio stations by kind, live streams first (as on the TV). */
@Composable
fun RadioScreen(viewModel: MusicTabsViewModel = hiltViewModel()) {
    val groups by viewModel.radio.collectAsStateWithLifecycle(initialValue = null)
    TabPage(title = stringResource(R.string.nav_radio)) { padding, comingSoon ->
        val list = groups
        if (list.isNullOrEmpty()) {
            LoadingOrEmpty(list == null, stringResource(R.string.nothing_here), Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                list.forEach { group ->
                    item(key = "header-${group.kind}") {
                        Text(
                            stringResource(group.kind.label()),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(group.stations, key = { it.id }) { station ->
                        ListItem(
                            headlineContent = { Text(station.name) },
                            supportingContent = station.description.takeIf { it.isNotEmpty() }?.let {
                                { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            },
                            leadingContent = {
                                Art(station.imageUri, station.name, Modifier.size(48.dp), RoundedCornerShape(6.dp))
                            },
                            modifier = Modifier.clickable(onClick = comingSoon),
                        )
                    }
                }
            }
        }
    }
}

private fun StationKind.label(): Int = when (this) {
    StationKind.Stream -> R.string.radio_streams
    StationKind.Genre -> R.string.radio_genres
    StationKind.Period -> R.string.radio_decades
    else -> R.string.radio_other
}

/** Now Playing; the full player arrives with music playback. */
@Composable
fun PlayerScreen(main: MainViewModel) {
    val playing by main.nowPlaying.collectAsStateWithLifecycle()
    TabPage(title = stringResource(R.string.nav_player)) { padding, _ ->
        val item = playing.current
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (item == null) {
                Text(stringResource(R.string.nothing_playing), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.nothing_playing_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Art(item.imageUri, item.album, Modifier.size(280.dp))
                Text(item.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                Text(item.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
