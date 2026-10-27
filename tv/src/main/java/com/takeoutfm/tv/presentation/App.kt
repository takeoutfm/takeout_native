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

package com.takeoutfm.tv.presentation

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.takeoutfm.tv.presentation.screens.Screens
import com.takeoutfm.tv.presentation.screens.categories.CategoryMovieListScreen
import com.takeoutfm.tv.presentation.screens.dashboard.DashboardScreen
import com.takeoutfm.tv.presentation.screens.login.LoginScreen
import com.takeoutfm.tv.presentation.screens.login.SessionViewModel
import com.takeoutfm.tv.presentation.screens.movies.MovieDetailsScreen
import com.takeoutfm.tv.presentation.screens.music.album.AlbumScreen
import com.takeoutfm.tv.presentation.screens.music.artist.ArtistScreen
import com.takeoutfm.tv.presentation.screens.music.player.MiniPlayer
import com.takeoutfm.tv.presentation.screens.music.player.MusicPlayerScreen
import com.takeoutfm.tv.presentation.screens.music.podcasts.PodcastScreen
import com.takeoutfm.tv.presentation.screens.music.player.NowPlayingScreen
import com.takeoutfm.tv.presentation.screens.person.PersonScreen
import com.takeoutfm.tv.presentation.screens.videoPlayer.VideoPlayerScreen
import com.takeoutfm.tv.presentation.utils.FocusReturn
import com.takeoutfm.tv.presentation.utils.LocalFocusReturn

@Composable
fun App(
    onBackPressed: () -> Unit
) {
    val sessionViewModel: SessionViewModel = hiltViewModel()
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    if (session.isLoggedIn) {
        // Also fires right after sign-in, when this first composes in an already-started app.
        LifecycleEventEffect(Lifecycle.Event.ON_START) { sessionViewModel.refreshLibrary() }
        AppContent(onBackPressed = onBackPressed)
    } else {
        LoginScreen()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppContent(
    onBackPressed: () -> Unit
) {

    val navController = rememberNavController()
    var isComingBackFromDifferentScreen by remember { mutableStateOf(false) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route.orEmpty()
    // The players show the music themselves (or are video, which pauses it).
    val showMiniPlayer = listOf(Screens.VideoPlayer, Screens.MusicPlayer, Screens.NowPlaying)
        .none { route.startsWith(it.name) }

    // Lets the mini player send focus back to the screen's last focused card or row.
    val focusReturn = remember { FocusReturn() }
    // When the user last pressed a D-pad direction, to tell moving into the mini player from
    // focus falling into it.
    var lastNavigationKeyAt by remember { mutableLongStateOf(0L) }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.keyCode in DirectionKeys) {
                    lastNavigationKeyAt = SystemClock.uptimeMillis()
                }
                false
            }
    ) {
        CompositionLocalProvider(LocalFocusReturn provides focusReturn) {
        NavHost(
            modifier = Modifier
                .onFocusedBoundsChanged { it?.let(focusReturn::onFocused) }
                .onFocusChanged { focusReturn.onContentFocusChanged(it.hasFocus) }
                .focusRequester(focusReturn.content)
                .focusGroup(),
            navController = navController,
            startDestination = Screens.Dashboard(),
            builder = {
                composable(
                    route = Screens.CategoryMovieList(),
                    arguments = listOf(
                        navArgument(CategoryMovieListScreen.CategoryIdBundleKey) {
                            type = NavType.StringType
                        }
                    )
                ) {
                    CategoryMovieListScreen(
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        },
                        onMovieSelected = { movie ->
                            navController.navigate(
                                Screens.MovieDetails.withArgs(movie.id)
                            )
                        }
                    )
                }
                composable(
                    route = Screens.MovieDetails(),
                    arguments = listOf(
                        navArgument(MovieDetailsScreen.MovieIdBundleKey) {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val movieId = backStackEntry.arguments
                        ?.getString(MovieDetailsScreen.MovieIdBundleKey)
                    MovieDetailsScreen(
                        goToMoviePlayer = { startOver ->
                            if (movieId != null) {
                                val route = Screens.VideoPlayer.withArgs(movieId)
                                navController.navigate(
                                    if (startOver) {
                                        "$route?${VideoPlayerScreen.StartOverBundleKey}=true"
                                    } else {
                                        route
                                    }
                                )
                            }
                        },
                        openPerson = { personId ->
                            navController.navigate(Screens.Person.withArgs(personId))
                        },
                        playEpisode = { episodeId ->
                            navController.navigate(Screens.VideoPlayer.withArgs(episodeId))
                        },
                        // A new details page on top, so Back returns to this one.
                        refreshScreenWithNewMovie = { movie ->
                            navController.navigate(Screens.MovieDetails.withArgs(movie.id))
                        },
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        }
                    )
                }
                composable(
                    route = Screens.Person(),
                    arguments = listOf(
                        navArgument(PersonScreen.PersonIdBundleKey) {
                            type = NavType.StringType
                        }
                    )
                ) {
                    PersonScreen(
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        },
                        onMovieSelected = { movie ->
                            navController.navigate(Screens.MovieDetails.withArgs(movie.id))
                        }
                    )
                }
                composable(route = Screens.Dashboard()) {
                    DashboardScreen(
                        openCategoryMovieList = { categoryId ->
                            navController.navigate(
                                Screens.CategoryMovieList.withArgs(categoryId)
                            )
                        },
                        openMovieDetailsScreen = { movieId ->
                            navController.navigate(
                                Screens.MovieDetails.withArgs(movieId)
                            )
                        },
                        openVideoPlayer = { movie ->
                            navController.navigate(Screens.VideoPlayer.withArgs(movie.id))
                        },
                        openArtist = { artistId ->
                            navController.navigate(Screens.Artist.withArgs(artistId))
                        },
                        openAlbum = { albumId ->
                            navController.navigate(Screens.Album.withArgs(albumId))
                        },
                        openPodcast = { seriesId ->
                            navController.navigate(Screens.Podcast.withArgs(seriesId))
                        },
                        playMusic = { ref ->
                            navController.navigate(Screens.MusicPlayer.withArgs(ref.encode()))
                        },
                        onBackPressed = onBackPressed,
                        isComingBackFromDifferentScreen = isComingBackFromDifferentScreen,
                        resetIsComingBackFromDifferentScreen = {
                            isComingBackFromDifferentScreen = false
                        }
                    )
                }
                composable(
                    route = Screens.VideoPlayer() +
                        "?${VideoPlayerScreen.StartOverBundleKey}={${VideoPlayerScreen.StartOverBundleKey}}",
                    arguments = listOf(
                        navArgument(VideoPlayerScreen.MovieIdBundleKey) {
                            type = NavType.StringType
                        },
                        navArgument(VideoPlayerScreen.StartOverBundleKey) {
                            type = NavType.BoolType
                            defaultValue = false
                        }
                    )
                ) {
                    VideoPlayerScreen(
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        },
                        onPlayEpisode = { episodeId ->
                            // Replace this player rather than stacking one per episode, so Back
                            // still returns to where playback started.
                            navController.popBackStack()
                            navController.navigate(Screens.VideoPlayer.withArgs(episodeId))
                        }
                    )
                }
                composable(
                    route = Screens.Artist(),
                    arguments = listOf(
                        navArgument(ArtistScreen.ArtistIdBundleKey) { type = NavType.StringType }
                    )
                ) {
                    ArtistScreen(
                        onAlbumClick = { album ->
                            navController.navigate(Screens.Album.withArgs(album.id))
                        },
                        onArtistClick = { artist ->
                            navController.navigate(Screens.Artist.withArgs(artist.id))
                        },
                        onPlay = { ref ->
                            navController.navigate(Screens.MusicPlayer.withArgs(ref.encode()))
                        },
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        },
                    )
                }
                composable(
                    route = Screens.Album(),
                    arguments = listOf(
                        navArgument(AlbumScreen.AlbumIdBundleKey) { type = NavType.StringType }
                    )
                ) {
                    AlbumScreen(
                        onArtistClick = { artistId ->
                            navController.navigate(Screens.Artist.withArgs(artistId))
                        },
                        onAlbumClick = { album ->
                            navController.navigate(Screens.Album.withArgs(album.id))
                        },
                        onPlay = { ref ->
                            navController.navigate(Screens.MusicPlayer.withArgs(ref.encode()))
                        },
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        },
                    )
                }
                composable(
                    route = Screens.Podcast(),
                    arguments = listOf(
                        navArgument(PodcastScreen.SeriesIdBundleKey) { type = NavType.StringType }
                    )
                ) {
                    PodcastScreen(
                        onPlay = { ref ->
                            navController.navigate(Screens.MusicPlayer.withArgs(ref.encode()))
                        },
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        },
                    )
                }
                composable(
                    route = Screens.MusicPlayer(),
                    arguments = listOf(
                        navArgument(MusicPlayerScreen.QueueBundleKey) { type = NavType.StringType }
                    )
                ) {
                    MusicPlayerScreen(
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        },
                    )
                }
                composable(route = Screens.NowPlaying()) {
                    NowPlayingScreen(
                        onBackPressed = {
                            if (navController.navigateUp()) {
                                isComingBackFromDifferentScreen = true
                            }
                        },
                    )
                }
            }
        )
        }
        if (showMiniPlayer) {
            MiniPlayer(
                onReturnFocus = focusReturn::restore,
                // Focus that falls in (the page under it changed and has nothing focusable yet)
                // goes back to the page once it can.
                isContentFocusable = { focusReturn.restore() },
                lastNavigationKeyAt = { lastNavigationKeyAt },
                onOpenNowPlaying = {
                    navController.navigate(Screens.NowPlaying()) { launchSingleTop = true }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 24.dp),
            )
        }
    }
}

private val DirectionKeys = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)
