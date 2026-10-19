package com.google.jetstream.presentation.screens.person

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.jetstream.data.entities.PersonDetails
import com.google.jetstream.data.repositories.TakeoutMovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class PersonScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: TakeoutMovieRepository,
) : ViewModel() {
    val uiState = savedStateHandle
        .getStateFlow<String?>(PersonScreen.PersonIdBundleKey, null)
        .map { id ->
            if (id == null) {
                PersonScreenUiState.Error
            } else {
                try {
                    PersonScreenUiState.Done(repository.getPersonDetails(id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PersonScreenUiState.Error
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PersonScreenUiState.Loading
        )
}

@Immutable
sealed class PersonScreenUiState {
    data object Loading : PersonScreenUiState()
    data object Error : PersonScreenUiState()
    data class Done(val person: PersonDetails) : PersonScreenUiState()
}
