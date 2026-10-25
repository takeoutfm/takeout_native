package com.takeoutfm.tv.presentation.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.tv.data.entities.Recommendation
import com.takeoutfm.tv.data.repositories.TakeoutMovieRepository
import com.takeoutfm.shared.library.LibraryIndex
import com.takeoutfm.tv.data.takeout.LibraryMode
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.tv.data.takeout.TakeoutSettings
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
