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

package com.takeoutfm.tv.presentation.screens.dashboard

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.takeoutfm.tv.data.entities.Movie
import com.takeoutfm.tv.data.entities.Recommendation
import com.takeoutfm.tv.data.takeout.LibraryMode
import com.takeoutfm.tv.presentation.common.PosterGrid
import com.takeoutfm.tv.presentation.screens.Screens
import com.takeoutfm.tv.presentation.screens.categories.CategoriesScreen
import com.takeoutfm.tv.presentation.screens.favourites.FavouritesScreen
import com.takeoutfm.tv.presentation.screens.home.HomeScreen
import com.takeoutfm.tv.presentation.screens.movies.MoviesScreen
import com.takeoutfm.tv.presentation.screens.music.artists.ArtistsScreen
import com.takeoutfm.tv.presentation.screens.music.favorites.MusicFavoritesScreen
import com.takeoutfm.tv.presentation.screens.music.home.MusicHomeScreen
import com.takeoutfm.tv.presentation.screens.music.player.QueueRef
import com.takeoutfm.tv.presentation.screens.music.playlists.PlaylistsScreen
import com.takeoutfm.tv.presentation.screens.music.radio.RadioScreen
import com.takeoutfm.tv.presentation.screens.profile.ProfileScreen
import com.takeoutfm.tv.presentation.screens.search.SearchScreen
import com.takeoutfm.tv.presentation.screens.shows.ShowsScreen
import com.takeoutfm.tv.presentation.utils.Padding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

val ParentPadding = PaddingValues(vertical = 16.dp, horizontal = 58.dp)

@Composable
fun rememberChildPadding(direction: LayoutDirection = LocalLayoutDirection.current): Padding {
    return remember {
        Padding(
            start = ParentPadding.calculateStartPadding(direction) + 8.dp,
            top = ParentPadding.calculateTopPadding(),
            end = ParentPadding.calculateEndPadding(direction) + 8.dp,
            bottom = ParentPadding.calculateBottomPadding()
        )
    }
}

@Composable
fun DashboardScreen(
    openCategoryMovieList: (categoryId: String) -> Unit,
    openMovieDetailsScreen: (movieId: String) -> Unit,
    openVideoPlayer: (Movie) -> Unit,
    isComingBackFromDifferentScreen: Boolean,
    resetIsComingBackFromDifferentScreen: () -> Unit,
    onBackPressed: () -> Unit,
    openArtist: (artistId: String) -> Unit = {},
    openAlbum: (albumId: String) -> Unit = {},
    playMusic: (QueueRef) -> Unit = {},
    dashboardViewModel: DashboardViewModel = hiltViewModel(),
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val navController = rememberNavController()

    var isTopBarVisible by remember { mutableStateOf(true) }
    var isTopBarFocused by remember { mutableStateOf(false) }

    val recommendationsOrNull by dashboardViewModel.recommendations.collectAsStateWithLifecycle()
    val recommendations = recommendationsOrNull.orEmpty()
    val libraryIndex by dashboardViewModel.libraryIndex.collectAsStateWithLifecycle()
    val mode by dashboardViewModel.mode.collectAsStateWithLifecycle()
    val canSwitchMode = libraryIndex?.hasMusic == true
    // A library without music (once known) always shows video.
    LaunchedEffect(libraryIndex, mode) {
        val index = libraryIndex
        if (index != null && !index.hasMusic && mode == LibraryMode.Music) {
            dashboardViewModel.setMode(LibraryMode.Video)
        }
    }
    val tabs = remember(recommendations, libraryIndex, mode) {
        dashboardTabs(recommendations, libraryIndex, mode)
    }
    // The first tab shown when the dashboard is created, by the saved mode.
    val startDestination = remember {
        if (mode == LibraryMode.Music) Screens.MusicHome() else Screens.Home()
    }
    // +1 for the profile avatar, which comes first.
    val topBarFocusRequesters = remember(tabs.size) { List(tabs.size + 1) { FocusRequester() } }

    // The current tab's route, with a recommendation page's index filled in.
    var currentDestination: String? by remember { mutableStateOf(null) }
    val currentTopBarSelectedTabIndex by remember(currentDestination, tabs) {
        derivedStateOf {
            currentDestination?.let { route -> tabs.indexOfFirst { it.route == route } } ?: 0
        }
    }

    // If the library index drops the tab being shown (e.g. a seasonal set ends), go Home.
    LaunchedEffect(tabs, currentDestination, recommendationsOrNull) {
        val destination = currentDestination ?: return@LaunchedEffect
        if (recommendationsOrNull == null || destination == Screens.Profile()) return@LaunchedEffect
        if (tabs.none { it.route == destination }) {
            navController.navigate(tabs[0].route) {
                popUpTo(tabs[0].route)
                launchSingleTop = true
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = NavController.OnDestinationChangedListener { _, destination, arguments ->
            currentDestination = if (destination.route == RecommendedRoutePattern) {
                recommendedRoute(arguments?.getInt(RecommendedIndexKey) ?: 0)
            } else {
                destination.route
            }
        }

        navController.addOnDestinationChangedListener(listener)

        onDispose {
            navController.removeOnDestinationChangedListener(listener)
        }
    }

    BackPressHandledArea(
        // 1. On user's first back press, bring focus to the current selected tab, if TopBar is not
        //    visible, first make it visible, then focus the selected tab
        // 2. On second back press, bring focus back to the first displayed tab
        // 3. On third back press, exit the app
        onBackPressed = {
            if (!isTopBarVisible) {
                isTopBarVisible = true
                topBarFocusRequesters[currentTopBarSelectedTabIndex + 1].requestFocus()
            } else if (currentTopBarSelectedTabIndex == 0) onBackPressed()
            else if (!isTopBarFocused) {
                topBarFocusRequesters[currentTopBarSelectedTabIndex + 1].requestFocus()
            } else topBarFocusRequesters[1].requestFocus()
        }
    ) {
        // We do not want to focus the TopBar everytime we come back from another screen e.g.
        // MovieDetails, CategoryMovieList or VideoPlayer screen
        var wasTopBarFocusRequestedBefore by rememberSaveable { mutableStateOf(false) }

        var topBarHeightPx: Int by rememberSaveable { mutableIntStateOf(0) }

        // Used to show/hide DashboardTopBar
        val topBarYOffsetPx by animateIntAsState(
            targetValue = if (isTopBarVisible) 0 else -topBarHeightPx,
            animationSpec = tween(),
            label = "",
            finishedListener = {
                if (it == -topBarHeightPx && isComingBackFromDifferentScreen) {
                    focusManager.moveFocus(FocusDirection.Down)
                    resetIsComingBackFromDifferentScreen()
                }
            }
        )

        // Used to push down/pull up NavHost when DashboardTopBar is shown/hidden
        val navHostTopPaddingDp by animateDpAsState(
            targetValue = if (isTopBarVisible) with(density) { topBarHeightPx.toDp() } else 0.dp,
            animationSpec = tween(),
            label = "",
        )

        // Coming back from a title (or a genre, person, the player), the screen that had focus
        // goes away once its exit animation ends, and focus falls into the top bar (its current
        // tab). Move it down into the page instead, where the grid or row returns it to the item
        // that was opened (FocusMemory).
        LaunchedEffect(isComingBackFromDifferentScreen) {
            if (!isComingBackFromDifferentScreen) return@LaunchedEffect
            val fellToTopBar = withTimeoutOrNull(ReturnFocusTimeoutMillis) {
                snapshotFlow { isTopBarFocused }.first { it }
            }
            if (fellToTopBar != null) focusManager.moveFocus(FocusDirection.Down)
            resetIsComingBackFromDifferentScreen()
        }

        LaunchedEffect(Unit) {
            if (!wasTopBarFocusRequestedBefore) {
                topBarFocusRequesters[currentTopBarSelectedTabIndex + 1].requestFocus()
                wasTopBarFocusRequestedBefore = true
            }
        }

        DashboardTopBar(
            modifier = Modifier
                .offset { IntOffset(x = 0, y = topBarYOffsetPx) }
                .onSizeChanged { topBarHeightPx = it.height }
                .onFocusChanged { isTopBarFocused = it.hasFocus }
                .padding(
                    horizontal = ParentPadding.calculateStartPadding(
                        LocalLayoutDirection.current
                    ) + 8.dp
                )
                .padding(
                    top = ParentPadding.calculateTopPadding(),
                    bottom = ParentPadding.calculateBottomPadding()
                ),
            selectedTabIndex = currentTopBarSelectedTabIndex,
            tabs = tabs,
            focusRequesters = topBarFocusRequesters,
            mode = mode,
            canSwitchMode = canSwitchMode,
            onSwitchMode = {
                val newMode =
                    if (mode == LibraryMode.Music) LibraryMode.Video else LibraryMode.Music
                dashboardViewModel.setMode(newMode)
                // Start the other half fresh at its first tab.
                val firstTab = dashboardTabs(recommendations, libraryIndex, newMode)[0]
                navController.navigate(firstTab.route) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            },
        ) { targetRoute ->
            if (currentDestination != targetRoute) {
                navController.navigate(targetRoute) {
                    if (targetRoute == tabs[0].route) popUpTo(tabs[0].route)
                    launchSingleTop = true
                }
            }
        }

        Body(
            recommendations = recommendations,
            openCategoryMovieList = openCategoryMovieList,
            openMovieDetailsScreen = openMovieDetailsScreen,
            openVideoPlayer = openVideoPlayer,
            openArtist = openArtist,
            openAlbum = openAlbum,
            playMusic = playMusic,
            startDestination = startDestination,
            updateTopBarVisibility = { isTopBarVisible = it },
            isTopBarVisible = isTopBarVisible,
            navController = navController,
            modifier = Modifier.offset(y = navHostTopPaddingDp),
        )
    }
}

@Composable
private fun BackPressHandledArea(
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) =
    Box(
        modifier = Modifier
            .onPreviewKeyEvent {
                if (it.key == Key.Back && it.type == KeyEventType.KeyUp) {
                    onBackPressed()
                    true
                } else {
                    false
                }
            }
            .then(modifier),
        content = content
    )

@Composable
private fun Body(
    recommendations: List<Recommendation>,
    openCategoryMovieList: (categoryId: String) -> Unit,
    openMovieDetailsScreen: (movieId: String) -> Unit,
    openVideoPlayer: (Movie) -> Unit,
    openArtist: (artistId: String) -> Unit,
    openAlbum: (albumId: String) -> Unit,
    playMusic: (QueueRef) -> Unit,
    startDestination: String,
    updateTopBarVisibility: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    isTopBarVisible: Boolean = true,
) =
    NavHost(
        modifier = modifier,
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Screens.Profile()) {
            ProfileScreen()
        }
        composable(Screens.Home()) {
            HomeScreen(
                onMovieClick = { selectedMovie ->
                    openMovieDetailsScreen(selectedMovie.id)
                },
                goToVideoPlayer = openVideoPlayer,
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible
            )
        }
        composable(
            route = RecommendedRoutePattern,
            arguments = listOf(navArgument(RecommendedIndexKey) { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt(RecommendedIndexKey) ?: 0
            PosterGrid(
                movieList = recommendations.getOrNull(index)?.movies.orEmpty(),
                onMovieClick = { movie -> openMovieDetailsScreen(movie.id) },
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(Screens.Genres()) {
            CategoriesScreen(
                onCategoryClick = openCategoryMovieList,
                onScroll = updateTopBarVisibility
            )
        }
        composable(Screens.Movies()) {
            MoviesScreen(
                onMovieClick = { movie -> openMovieDetailsScreen(movie.id) },
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible
            )
        }
        composable(Screens.Shows()) {
            ShowsScreen(
                onTVShowClick = { movie -> openMovieDetailsScreen(movie.id) },
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible
            )
        }
        composable(Screens.Favorites()) {
            FavouritesScreen(
                onMovieClick = openMovieDetailsScreen,
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible
            )
        }
        composable(Screens.Search()) {
            SearchScreen(
                onMovieClick = { movie -> openMovieDetailsScreen(movie.id) },
                onScroll = updateTopBarVisibility
            )
        }
        composable(Screens.MusicHome()) {
            MusicHomeScreen(
                onAlbumClick = { openAlbum(it.id) },
                onPlay = playMusic,
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible,
            )
        }
        composable(Screens.Artists()) {
            ArtistsScreen(
                onArtistClick = { openArtist(it.id) },
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible,
            )
        }
        composable(Screens.MusicFavorites()) {
            MusicFavoritesScreen(
                onArtistClick = { openArtist(it.id) },
                onPlay = playMusic,
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible,
            )
        }
        composable(Screens.Playlists()) {
            PlaylistsScreen(
                onPlaylistClick = { playMusic(QueueRef.Playlist(it.id)) },
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible,
            )
        }
        composable(Screens.Radio()) {
            RadioScreen(
                onStationClick = { playMusic(QueueRef.Station(it.id)) },
                onScroll = updateTopBarVisibility,
                isTopBarVisible = isTopBarVisible,
            )
        }
    }

private const val ReturnFocusTimeoutMillis = 2_000L
