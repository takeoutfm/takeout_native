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

package com.google.jetstream.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.jetstream.presentation.screens.Screens
import com.google.jetstream.presentation.screens.categories.CategoryMovieListScreen
import com.google.jetstream.presentation.screens.dashboard.DashboardScreen
import com.google.jetstream.presentation.screens.login.LoginScreen
import com.google.jetstream.presentation.screens.login.SessionViewModel
import com.google.jetstream.presentation.screens.movies.MovieDetailsScreen
import com.google.jetstream.presentation.screens.person.PersonScreen
import com.google.jetstream.presentation.screens.videoPlayer.VideoPlayerScreen

@Composable
fun App(
    onBackPressed: () -> Unit
) {
    val sessionViewModel: SessionViewModel = hiltViewModel()
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    if (session.isLoggedIn) {
        AppContent(onBackPressed = onBackPressed)
    } else {
        LoginScreen()
    }
}

@Composable
private fun AppContent(
    onBackPressed: () -> Unit
) {

    val navController = rememberNavController()
    var isComingBackFromDifferentScreen by remember { mutableStateOf(false) }

    NavHost(
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
                    refreshScreenWithNewMovie = { movie ->
                        navController.navigate(
                            Screens.MovieDetails.withArgs(movie.id)
                        ) {
                            popUpTo(Screens.MovieDetails()) {
                                inclusive = true
                            }
                        }
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
                    }
                )
            }
        }
    )
}
