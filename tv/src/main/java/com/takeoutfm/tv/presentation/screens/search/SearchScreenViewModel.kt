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

package com.takeoutfm.tv.presentation.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.shared.music.MusicSearchResults
import com.takeoutfm.shared.music.Track
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.tv.data.entities.SearchResults
import com.takeoutfm.tv.data.repositories.TakeoutMovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Searches video and music at once (LIB-12). Movies and shows are what the TV is mostly for, so
 * the video results show as soon as they come, and music's follow below them.
 */
@HiltViewModel
class SearchScreenViewModel @Inject constructor(
    private val repository: TakeoutMovieRepository,
    private val music: TakeoutMusicRepository,
    private val player: MusicPlayer,
) : ViewModel() {

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Done(SearchResults(), MusicSearchResults()))
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private var searching: Job? = null

    fun query(queryString: String) {
        searching?.cancel()
        searching = viewModelScope.launch {
            _searchState.value = SearchState.Searching
            val musicResults = async { safely { music.search(queryString) } ?: MusicSearchResults() }
            val video = safely { repository.search(query = queryString) } ?: SearchResults()
            _searchState.value = SearchState.Done(video, null)
            _searchState.value = SearchState.Done(video, musicResults.await())
        }
    }

    /** Plays the search's [tracks] from the [index]th, as the phone does (the rest follow). */
    fun playTracks(query: String, tracks: List<Track>, index: Int) {
        player.play(music.tracksQueue(query, tracks, index))
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

sealed interface SearchState {
    data object Searching : SearchState

    /** [music] null while it's still coming. */
    data class Done(val results: SearchResults, val music: MusicSearchResults?) : SearchState
}
