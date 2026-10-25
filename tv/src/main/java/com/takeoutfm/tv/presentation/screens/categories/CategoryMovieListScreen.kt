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

package com.takeoutfm.tv.presentation.screens.categories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.takeoutfm.tv.data.entities.Movie
import com.takeoutfm.tv.data.entities.MovieCategoryDetails
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.PosterGrid
import com.takeoutfm.tv.presentation.common.PosterGridSkeleton
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding

object CategoryMovieListScreen {
    const val CategoryIdBundleKey = "categoryId"
}

@Composable
fun CategoryMovieListScreen(
    onBackPressed: () -> Unit,
    onMovieSelected: (Movie) -> Unit,
    categoryMovieListScreenViewModel: CategoryMovieListScreenViewModel = hiltViewModel()
) {
    val uiState by categoryMovieListScreenViewModel.uiState.collectAsStateWithLifecycle()

    when (val s = uiState) {
        CategoryMovieListScreenUiState.Loading -> {
            PosterGridSkeleton(modifier = Modifier.fillMaxSize(), showHeader = true)
        }

        CategoryMovieListScreenUiState.Error -> {
            Error(modifier = Modifier.fillMaxSize())
        }

        is CategoryMovieListScreenUiState.Done -> {
            val categoryDetails = s.movieCategoryDetails
            CategoryDetails(
                categoryDetails = categoryDetails,
                onBackPressed = onBackPressed,
                onMovieSelected = onMovieSelected
            )
        }
    }
}

@Composable
private fun CategoryDetails(
    categoryDetails: MovieCategoryDetails,
    onBackPressed: () -> Unit,
    onMovieSelected: (Movie) -> Unit,
    modifier: Modifier = Modifier
) {
    val childPadding = rememberChildPadding()

    BackHandler(onBack = onBackPressed)

    PosterGrid(
        movieList = categoryDetails.movies,
        onMovieClick = onMovieSelected,
        focusFirstItem = true,
        modifier = modifier,
        header = {
            Text(
                text = categoryDetails.name,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = childPadding.top.times(2.5f))
            )
        }
    )
}
