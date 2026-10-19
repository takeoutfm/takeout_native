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

package com.google.jetstream.presentation.screens.favourites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.jetstream.R
import com.google.jetstream.presentation.common.Loading
import com.google.jetstream.presentation.common.PosterGrid

@Composable
fun FavouritesScreen(
    onMovieClick: (movieId: String) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    favouriteScreenViewModel: FavouriteScreenViewModel = hiltViewModel()
) {
    val uiState by favouriteScreenViewModel.uiState.collectAsStateWithLifecycle()
    when (val s = uiState) {
        is FavouriteScreenUiState.Loading -> {
            Loading(modifier = Modifier.fillMaxSize())
        }
        is FavouriteScreenUiState.Ready -> {
            if (!s.hasFavourites) {
                NoFavourites(modifier = Modifier.fillMaxSize())
            } else {
                PosterGrid(
                    movieList = s.favouriteMovieList,
                    onMovieClick = { movie -> onMovieClick(movie.id) },
                    onScroll = onScroll,
                    isTopBarVisible = isTopBarVisible,
                    modifier = Modifier.fillMaxSize(),
                    header = {
                        MovieFilterChipRow(
                            filterList = FavouriteScreenViewModel.filterList,
                            selectedFilterList = s.selectedFilterList,
                            onSelectedFilterListUpdated =
                            favouriteScreenViewModel::updateSelectedFilterList
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun NoFavourites(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.favorites_empty),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 64.dp)
                .alpha(0.75f)
        )
    }
}
