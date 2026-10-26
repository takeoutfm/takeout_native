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
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    com.takeoutfm.mobile.ui.common.BarTitle(title, subtitle)
                },
            )
        },
        content = content,
    )
}

/** Artists A–Z, or favorite artists; re-tapping the tab switches. */
@Composable
fun ArtistsScreen(main: MainViewModel, nav: MusicNav, viewModel: MusicTabsViewModel = hiltViewModel()) {
    val type by main.artistType.collectAsStateWithLifecycle()
    val artistsFlow = remember(type) { viewModel.artists(type) }
    val artists by artistsFlow.collectAsStateWithLifecycle(initialValue = null)
    TabPage(
        title = stringResource(R.string.nav_artists),
        subtitle = stringResource(if (type == ArtistType.All) R.string.artists_all else R.string.artists_favorite),
    ) { padding ->
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
                        modifier = Modifier.clickable { nav.openArtist(artist.id) },
                    )
                }
            }
        }
    }
}
