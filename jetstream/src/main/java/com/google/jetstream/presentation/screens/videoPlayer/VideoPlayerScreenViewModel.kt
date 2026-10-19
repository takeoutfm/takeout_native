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

package com.google.jetstream.presentation.screens.videoPlayer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.jetstream.data.entities.MovieDetails
import com.google.jetstream.data.repositories.MovieRepository
import com.google.jetstream.data.takeout.TakeoutProgress
import com.google.jetstream.data.takeout.TakeoutSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

@HiltViewModel
class VideoPlayerScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: MovieRepository,
    val progress: TakeoutProgress,
    val settings: TakeoutSettings,
) : ViewModel() {
    val startOver: Boolean =
        savedStateHandle.get<Boolean>(VideoPlayerScreen.StartOverBundleKey) ?: false

    private val retries = MutableStateFlow(0)

    /** Loads again after a failure (the error screen's Try again). */
    fun retry() {
        retries.value++
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        savedStateHandle.getStateFlow<String?>(VideoPlayerScreen.MovieIdBundleKey, null),
        retries,
    ) { id, _ -> id }
        .transformLatest { id ->
            if (id == null) {
                emit(VideoPlayerScreenUiState.Error)
                return@transformLatest
            }
            emit(VideoPlayerScreenUiState.Loading)
            val state = try {
                val details = repository.getMovieDetails(movieId = id)
                VideoPlayerScreenUiState.Done(movieDetails = details)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VideoPlayerScreenUiState.Error
            }
            emit(state)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VideoPlayerScreenUiState.Loading
        )
}

@Immutable
sealed class VideoPlayerScreenUiState {
    data object Loading : VideoPlayerScreenUiState()
    data object Error : VideoPlayerScreenUiState()
    data class Done(val movieDetails: MovieDetails) : VideoPlayerScreenUiState()
}
