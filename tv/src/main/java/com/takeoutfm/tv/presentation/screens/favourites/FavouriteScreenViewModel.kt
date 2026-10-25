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

package com.takeoutfm.tv.presentation.screens.favourites

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.tv.R
import com.takeoutfm.tv.data.entities.Movie
import com.takeoutfm.tv.data.entities.MovieList
import com.takeoutfm.tv.data.repositories.MovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class FavouriteScreenViewModel @Inject constructor(
    movieRepository: MovieRepository
) : ViewModel() {

    private val selectedFilterList = MutableStateFlow(FilterList())

    val uiState: StateFlow<FavouriteScreenUiState> = combine(
        selectedFilterList,
        movieRepository.getFavouriteMovies()
    ) { filterList, movieList ->
        // No chip selected shows everything.
        val filtered = if (filterList.items.isEmpty()) {
            movieList
        } else {
            movieList.filter { movie -> filterList.items.any { it.matches(movie) } }
        }
        FavouriteScreenUiState.Ready(filtered, filterList, hasFavourites = movieList.isNotEmpty())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FavouriteScreenUiState.Loading
    )

    fun updateSelectedFilterList(filterList: FilterList) {
        selectedFilterList.value = filterList
    }

    companion object {
        val filterList = FilterList(FilterCondition.entries)
    }
}

sealed interface FavouriteScreenUiState {
    data object Loading : FavouriteScreenUiState
    data class Ready(
        val favouriteMovieList: MovieList,
        val selectedFilterList: FilterList,
        /** False when the user has no favorites at all (as opposed to none matching a chip). */
        val hasFavourites: Boolean,
    ) : FavouriteScreenUiState
}

@Immutable
data class FilterList(val items: List<FilterCondition> = emptyList())

/** A chip on the Favourites tab; matches by the id prefix of the kind of title. */
@Immutable
enum class FilterCondition(private val idPrefix: String, @StringRes val labelId: Int) {
    Movies("m-", R.string.favorites_movies),
    TvShows("s-", R.string.favorites_tv_shows);

    fun matches(movie: Movie) = movie.id.startsWith(idPrefix)
}
