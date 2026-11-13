package com.takeoutfm.android.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.android.BuildConfig
import com.takeoutfm.android.R
import com.takeoutfm.android.ui.common.BarTitle
import com.takeoutfm.android.ui.common.LoadingOrEmpty
import com.takeoutfm.android.ui.common.TileGrid
import com.takeoutfm.android.ui.main.MainViewModel
import com.takeoutfm.android.ui.music.MusicNav
import com.takeoutfm.android.ui.music.QueueKind
import com.takeoutfm.android.ui.video.GenreGrid
import androidx.hilt.navigation.compose.hiltViewModel
import com.takeoutfm.android.ui.live.LiveHome
import com.takeoutfm.android.ui.video.VideoNav
import kotlinx.coroutines.launch

/**
 * Home: the media bar (one button per media type the library has; tapping the shown one cycles
 * its lists) over a cover grid, with the Flutter app's menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    main: MainViewModel,
    nav: MusicNav,
    videoNav: VideoNav,
    onSettings: () -> Unit,
    onLink: () -> Unit,
) {
    val types by viewModel.types.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var about by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Six 48dp buttons leave a 360dp phone about 60dp for the title ("Mu…"): there,
            // pack them at their 40dp visual size and use the smaller title style.
            val width = LocalConfiguration.current.screenWidthDp.dp
            val narrow = width < 48.dp * (types.size + 2) + TitleRoom
            // Still too little for "Recently added" (a 360dp phone): Live goes in the menu.
            val inMenu = if (narrow && width < 40.dp * (types.size + 2) + NarrowTitleRoom) {
                types.filter { it == MediaType.Live }
            } else emptyList()
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides if (narrow) 40.dp else 48.dp,
            ) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = nav.openSearch) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_label))
                        }
                    },
                    title = {
                        val style = if (narrow) MaterialTheme.typography.titleMedium else LocalTextStyle.current
                        CompositionLocalProvider(LocalTextStyle provides style) {
                            BarTitle(stringResource(selection.type.title), stringResource(selection.subtype.label), compact = narrow)
                        }
                    },
                    actions = {
                        (types - inMenu.toSet()).forEach { type ->
                            val selected = type == selection.type
                            IconButton(onClick = { viewModel.select(type) }) {
                                Icon(type.icon(selected), contentDescription = stringResource(type.title))
                            }
                        }
                        HomeMenu(
                            types = inMenu.map { stringResource(it.title) to { viewModel.select(it) } },
                            onReload = viewModel::reload,
                            onFavoriteTracks = {
                                nav.openQueue(QueueKind.Favorites, "", context.getString(R.string.favorite_tracks))
                            },
                            onPlaylists = nav.openPlaylists,
                            onRecentTracks = nav.openRecentTracks,
                            onActivity = nav.openActivity,
                            onDownloads = nav.openDownloads,
                            onLink = onLink,
                            onSettings = onSettings,
                            onAbout = { about = true },
                            onLogout = viewModel::logout,
                        )
                    },
                )
            }
        },
    ) { padding ->
        if (selection.type == MediaType.Live) {
            Box(Modifier.padding(padding)) {
                LiveHome(grid = selection.subtype == Subtype.LiveGuide, viewModel = hiltViewModel(), videoNav = videoNav)
            }
        } else PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::reload,
            modifier = Modifier.padding(padding),
        ) {
            if (content.tiles.isEmpty()) {
                LoadingOrEmpty(
                    loading = content.loading,
                    empty = stringResource(if (content.failed) R.string.cant_load else R.string.nothing_here),
                )
            } else if (content.tiles.first().id.startsWith(GenrePrefix)) {
                GenreGrid(content.tiles) { videoNav.openGenre(it.id.removePrefix(GenrePrefix)) }
            } else {
                TileGrid(
                    content.tiles,
                    onClick = { tile ->
                        when {
                            tile.id.startsWith("al-") -> nav.openAlbum(tile.id)
                            tile.id.startsWith("ps-") -> nav.openPodcast(tile.id)
                            else -> videoNav.openVideo(tile.id)
                        }
                    },
                )
            }
        }
    }
    if (about) AboutDialog(onDismiss = { about = false })
}

/** What the title and subtitle ("Recently added") need beside the bar's icons. */
private val TitleRoom = 120.dp

/** The same with the smaller title style: it fits from a 384dp phone up, not on a 360dp one. */
private val NarrowTitleRoom = 104.dp

private fun MediaType.icon(selected: Boolean): ImageVector = when (this) {
    MediaType.Music -> if (selected) Icons.Filled.Speaker else Icons.Outlined.Speaker
    MediaType.Film -> if (selected) Icons.Filled.Movie else Icons.Outlined.Movie
    MediaType.Tv -> if (selected) Icons.Filled.Tv else Icons.Outlined.Tv
    MediaType.Podcast -> if (selected) Icons.Filled.Podcasts else Icons.Outlined.Podcasts
    MediaType.Live -> if (selected) Icons.Filled.LiveTv else Icons.Outlined.LiveTv
}

@Composable
private fun HomeMenu(
    types: List<Pair<String, () -> Unit>>,
    onReload: () -> Unit,
    onFavoriteTracks: () -> Unit,
    onPlaylists: () -> Unit,
    onRecentTracks: () -> Unit,
    onActivity: () -> Unit,
    onDownloads: () -> Unit,
    onLink: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // media types with no room in the bar
            types.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
            if (types.isNotEmpty()) HorizontalDivider()
            listOf(
                R.string.refresh_label to onReload,
                R.string.recent_tracks to onRecentTracks,
                R.string.favorite_tracks to onFavoriteTracks,
                R.string.activity to onActivity,
                R.string.playlists to onPlaylists,
                R.string.settings_label to onSettings,
                R.string.downloads to onDownloads,
                R.string.link_label to onLink,
                R.string.about_label to onAbout,
                R.string.logout_label to onLogout,
            ).forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val home = stringResource(R.string.about_home)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                Modifier
                    .size(72.dp)
                    .background(colorResource(R.color.ic_launcher_background), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null)
            }
        },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column {
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
                Text(stringResource(R.string.about_copyleft))
                TextButton(onClick = { uri.openUri(home) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(home, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
