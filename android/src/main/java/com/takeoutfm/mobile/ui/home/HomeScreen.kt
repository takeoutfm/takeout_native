package com.takeoutfm.mobile.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.mobile.BuildConfig
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.common.TileGrid
import kotlinx.coroutines.launch

/**
 * Home: the media bar (one button per media type the library has; tapping the shown one cycles
 * its lists) over a cover grid, with the Flutter app's menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onSettings: () -> Unit) {
    val types by viewModel.types.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var about by remember { mutableStateOf(false) }
    val comingSoon = stringResource(R.string.coming_soon)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Column {
                        Text(stringResource(selection.type.title))
                        Text(
                            stringResource(selection.subtype.label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    types.forEach { type ->
                        val selected = type == selection.type
                        IconButton(onClick = { viewModel.select(type) }) {
                            Icon(type.icon(selected), contentDescription = stringResource(type.title))
                        }
                    }
                    HomeMenu(
                        onReload = viewModel::reload,
                        onSettings = onSettings,
                        onAbout = { about = true },
                        onLogout = viewModel::logout,
                    )
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::reload,
            modifier = Modifier.padding(padding),
        ) {
            if (content.tiles.isEmpty()) {
                LoadingOrEmpty(
                    loading = content.loading,
                    empty = stringResource(if (content.failed) R.string.cant_load else R.string.nothing_here),
                )
            } else {
                TileGrid(content.tiles, onClick = { scope.launch { snackbar.showSnackbar(comingSoon) } })
            }
        }
    }
    if (about) AboutDialog(onDismiss = { about = false })
}

private fun MediaType.icon(selected: Boolean): ImageVector = when (this) {
    MediaType.Music -> if (selected) Icons.Filled.Speaker else Icons.Outlined.Speaker
    MediaType.Film -> if (selected) Icons.Filled.Movie else Icons.Outlined.Movie
    MediaType.Tv -> if (selected) Icons.Filled.Tv else Icons.Outlined.Tv
}

@Composable
private fun HomeMenu(onReload: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit, onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                R.string.refresh_label to onReload,
                R.string.settings_label to onSettings,
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
