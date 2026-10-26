package com.takeoutfm.mobile.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowWidthSizeClass
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.home.HomeViewModel
import com.takeoutfm.mobile.ui.music.AddToPlaylistDialog
import com.takeoutfm.mobile.ui.music.MiniPlayer

/** The bottom tabs, as in the Flutter app (icons only). */
enum class Tab(
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home(R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Artists(R.string.nav_artists, Icons.Outlined.PeopleAlt, Icons.Filled.PeopleAlt),
    Recent(R.string.nav_history, Icons.Outlined.History, Icons.Filled.History),
    Radio(R.string.nav_radio, Icons.Outlined.Radio, Icons.Filled.Radio),
    Player(R.string.nav_player, Icons.Outlined.QueueMusic, Icons.Filled.QueueMusic),
}

/**
 * The signed-in app: a bottom bar on phones and a navigation rail on wider windows, one back
 * stack per tab (kept while switching tabs), and the play/pause button. Tapping the current
 * tab again goes back to its first page; at the first page, Home cycles the media type and
 * Artists cycles the artist type.
 */
@Composable
fun MainScaffold(
    homeViewModel: HomeViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val controllers = Tab.entries.associateWith { rememberNavController() }
    val stateHolder = rememberSaveableStateHolder()
    val current = controllers.getValue(tab)
    val entry by current.currentBackStackEntryAsState()
    val atRoot = entry == null || current.previousBackStackEntry == null

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        mainViewModel.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    LaunchedEffect(Unit) {
        mainViewModel.formatted.collect { (id, arg) -> snackbar.showSnackbar(context.getString(id, arg)) }
    }
    AddToPlaylistDialog(mainViewModel)
    NotificationPermission(mainViewModel)

    // Back at a tab's first page goes Home, as the Flutter app does.
    BackHandler(enabled = tab != Tab.Home && atRoot) { tab = Tab.Home }

    // A rail whenever the window isn't phone-portrait narrow (landscape phones too, as the
    // Flutter app), else the bottom bar.
    val widthClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    val layoutType = if (widthClass == WindowWidthSizeClass.COMPACT) {
        NavigationSuiteType.NavigationBar
    } else {
        NavigationSuiteType.NavigationRail
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            Tab.entries.forEach { t ->
                item(
                    selected = t == tab,
                    onClick = {
                        if (t != tab) {
                            tab = t
                        } else if (!controllers.getValue(t).popToRoot()) {
                            when (t) {
                                Tab.Home -> homeViewModel.nextMediaType()
                                Tab.Artists -> mainViewModel.nextArtistType()
                                else -> Unit
                            }
                        }
                    },
                    icon = {
                        Icon(
                            if (t == tab) t.selectedIcon else t.icon,
                            contentDescription = stringResource(t.label),
                        )
                    },
                    alwaysShowLabel = false,
                )
            }
        },
    ) {
        val playing by mainViewModel.nowPlaying.collectAsStateWithLifecycle()
        val rail = layoutType == NavigationSuiteType.NavigationRail
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                stateHolder.SaveableStateProvider(tab) {
                    TabHost(tab, current, homeViewModel, mainViewModel)
                }
                // Phones in portrait: the play/pause button, as the Flutter app; wide layouts
                // get the persistent mini player below instead.
                if (!rail) {
                    PlayPauseFab(
                        visible = tab != Tab.Player,
                        mainViewModel = mainViewModel,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (rail && tab != Tab.Player) {
                MiniPlayer(playing, mainViewModel, onOpen = { tab = Tab.Player })
            }
        }
    }
}

/** Pops to the tab's first page; false when it was already there. */
private fun NavHostController.popToRoot(): Boolean {
    if (previousBackStackEntry == null) return false
    val root = graph.startDestinationId
    return popBackStack(root, inclusive = false)
}

/** Play/pause for the music queue, with its progress around it; hidden with nothing loaded. */
@Composable
private fun PlayPauseFab(visible: Boolean, mainViewModel: MainViewModel, modifier: Modifier) {
    val playing by mainViewModel.nowPlaying.collectAsStateWithLifecycle()
    if (!visible || !playing.hasQueue) return
    Box(modifier, contentAlignment = Alignment.Center) {
        FloatingActionButton(onClick = mainViewModel::togglePlay) {
            Icon(
                if (playing.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (playing.playWhenReady) R.string.pause else R.string.play),
            )
        }
        if (!playing.isLive && playing.durationMs > 0) {
            CircularProgressIndicator(
                progress = { (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.size(52.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * The media notification (lock screen and shade controls) needs the notification permission
 * on Android 13+; ask the first time music starts.
 */
@Composable
private fun NotificationPermission(mainViewModel: MainViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val playing by mainViewModel.nowPlaying.collectAsStateWithLifecycle()
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playing.hasQueue) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (playing.hasQueue && !granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
