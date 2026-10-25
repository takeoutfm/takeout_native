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

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.tv.data.entities.MovieDetails
import com.takeoutfm.tv.data.repositories.MovieRepository
import com.takeoutfm.tv.data.repositories.TakeoutMovieRepository
import com.takeoutfm.shared.progress.TakeoutProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

@HiltViewModel
class MovieDetailsScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: MovieRepository,
    private val progress: TakeoutProgress,
    private val takeout: TakeoutMovieRepository,
) : ViewModel() {
    private val _isFavorite = MutableStateFlow(false)

    /** Starts from the loaded details; flipped right away on toggle, reverted if the server fails. */
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    fun toggleFavorite(favoritePath: String) {
        val favorite = !_isFavorite.value
        _isFavorite.value = favorite
        viewModelScope.launch {
            try {
                takeout.setFavorite(favoritePath, favorite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isFavorite.value = !favorite
            }
        }
    }

    private val _resumePositionMs = MutableStateFlow(0L)

    /** Where Play would resume the title, in ms; 0 when it would start from the beginning. */
    val resumePositionMs: StateFlow<Long> = _resumePositionMs.asStateFlow()

    fun refreshResumePosition(etag: String) {
        viewModelScope.launch { _resumePositionMs.value = progress.resumePositionMs(etag) }
    }

    /**
     * The cast or episode card that last had focus, so Back from the person page or player
     * returns to it. Kept here because the screen reloads when it's returned to after a while.
     */
    val focusedCardKey = mutableStateOf<String?>(null)

    private var loaded: MovieDetails? = null

    private val retries = MutableStateFlow(0)

    /** Loads again after a failure (the error screen's Try again). */
    fun retry() {
        retries.value++
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        savedStateHandle.getStateFlow<String?>(MovieDetailsScreen.MovieIdBundleKey, null),
        retries,
    ) { id, _ -> id }
        .transformLatest { id ->
            if (id == null) {
                emit(MovieDetailsScreenUiState.Error)
                return@transformLatest
            }
            // Coming back to the screen after a while restarts this; keep showing what's loaded
            // while it refreshes, so the screen keeps its scroll and focus instead of being
            // rebuilt from a skeleton. (Unchanged details are equal, so nothing recomposes.)
            if (loaded == null) emit(MovieDetailsScreenUiState.Loading)
            val state = try {
                val details = repository.getMovieDetails(movieId = id)
                _isFavorite.value = details.isFavorite
                loaded = details
                MovieDetailsScreenUiState.Done(movieDetails = details)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed refresh keeps the loaded details on screen.
                loaded?.let { MovieDetailsScreenUiState.Done(movieDetails = it) }
                    ?: MovieDetailsScreenUiState.Error
            }
            emit(state)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MovieDetailsScreenUiState.Loading
        )
}

sealed class MovieDetailsScreenUiState {
    data object Loading : MovieDetailsScreenUiState()
    data object Error : MovieDetailsScreenUiState()
    data class Done(val movieDetails: MovieDetails) : MovieDetailsScreenUiState()
}
