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

package com.takeoutfm.tv.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.tv.data.entities.MovieList
import com.takeoutfm.tv.data.entities.Recommendation
import com.takeoutfm.tv.data.repositories.MovieRepository
import com.takeoutfm.tv.data.repositories.TakeoutMovieRepository
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.tv.data.takeout.TakeoutSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeScreeViewModel @Inject constructor(
    movieRepository: MovieRepository,
    takeout: TakeoutMovieRepository,
    settings: TakeoutSettings,
    private val library: TakeoutLibrary,
) : ViewModel() {

    private val refreshes = MutableStateFlow(0)

    /**
     * Re-reads progress, e.g. when Home is shown again after watching something, and refreshes
     * the library index if it's more than a few minutes old.
     */
    fun refreshContinueWatching() {
        refreshes.value++
        viewModelScope.launch { library.refresh(maxAgeMs = 5 * 60_000) }
    }

    /** Movies and episodes in progress, most recently watched first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatching: StateFlow<MovieList> =
        combine(settings.continueWatchingDays, refreshes) { days, _ -> days }
            .flatMapLatest { days -> takeout.getContinueWatching(days) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<HomeScreenUiState> = combine(
        movieRepository.getFeaturedMovies(),
        movieRepository.getTrendingMovies(),
        takeout.getRecommendations(long = true),
        movieRepository.getNowPlayingMovies(),
    ) { featuredMovieList, trendingMovieList, recommendations, nowPlayingMovieList ->
        HomeScreenUiState.Ready(
            featuredMovieList,
            trendingMovieList,
            recommendations.firstOrNull(),
            nowPlayingMovieList
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeScreenUiState.Loading
    )
}

sealed interface HomeScreenUiState {
    data object Loading : HomeScreenUiState
    data object Error : HomeScreenUiState
    data class Ready(
        val featuredMovieList: MovieList,
        val trendingMovieList: MovieList,
        /** The server's first current recommendation set, if any (each also has its own tab). */
        val recommended: Recommendation?,
        val nowPlayingMovieList: MovieList
    ) : HomeScreenUiState
}
