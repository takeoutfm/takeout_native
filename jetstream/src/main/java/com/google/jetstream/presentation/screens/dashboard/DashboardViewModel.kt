package com.google.jetstream.presentation.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.jetstream.data.entities.Recommendation
import com.google.jetstream.data.repositories.TakeoutMovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: TakeoutMovieRepository,
) : ViewModel() {
    /** Each set becomes a top bar tab between Home and Genres. */
    val recommendations: StateFlow<List<Recommendation>> = repository.getRecommendations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
