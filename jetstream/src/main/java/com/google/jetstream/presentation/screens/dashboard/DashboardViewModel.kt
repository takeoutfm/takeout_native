package com.google.jetstream.presentation.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.jetstream.data.entities.Recommendation
import com.google.jetstream.data.repositories.TakeoutMovieRepository
import com.google.jetstream.data.takeout.LibraryIndex
import com.google.jetstream.data.takeout.LibraryMode
import com.google.jetstream.data.takeout.TakeoutLibrary
import com.google.jetstream.data.takeout.TakeoutSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: TakeoutMovieRepository,
    library: TakeoutLibrary,
    private val settings: TakeoutSettings,
) : ViewModel() {
    /** Each set becomes a top bar tab between Home and Genres; null until first known. */
    val recommendations: StateFlow<List<Recommendation>?> = repository.getRecommendations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which kinds of tab the library has; null until the first index is known. */
    val libraryIndex: StateFlow<LibraryIndex?> = library.index

    /** Video or music tabs, switched with the TAKEOUT logo; remembered on the device. */
    val mode: StateFlow<LibraryMode> = settings.mode

    fun setMode(mode: LibraryMode) = settings.setMode(mode)
}
