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

package com.takeoutfm.tv.presentation.screens.movies

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.tv.data.entities.Movie
import com.takeoutfm.tv.presentation.common.PosterGrid
import com.takeoutfm.tv.presentation.common.PosterGridSkeleton

@Composable
fun MoviesScreen(
    onMovieClick: (movie: Movie) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    moviesScreenViewModel: MoviesScreenViewModel = hiltViewModel(),
) {
    val uiState by moviesScreenViewModel.uiState.collectAsStateWithLifecycle()
    when (val s = uiState) {
        is MoviesScreenUiState.Loading -> PosterGridSkeleton(Modifier.fillMaxSize())
        is MoviesScreenUiState.Ready -> {
            PosterGrid(
                movieList = s.movieList,
                onMovieClick = onMovieClick,
                onScroll = onScroll,
                isTopBarVisible = isTopBarVisible,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
