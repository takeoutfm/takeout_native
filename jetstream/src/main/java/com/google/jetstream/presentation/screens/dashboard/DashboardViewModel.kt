package com.google.jetstream.presentation.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.jetstream.data.entities.Recommendation
import com.google.jetstream.data.repositories.TakeoutMovieRepository
import com.google.jetstream.data.takeout.LibraryIndex
import com.google.jetstream.data.takeout.TakeoutLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: TakeoutMovieRepository,
    library: TakeoutLibrary,
) : ViewModel() {
    /** Each set becomes a top bar tab between Home and Genres; null until first known. */
    val recommendations: StateFlow<List<Recommendation>?> = repository.getRecommendations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which kinds of tab the library has; null until the first index is known. */
    val libraryIndex: StateFlow<LibraryIndex?> = library.index
}
