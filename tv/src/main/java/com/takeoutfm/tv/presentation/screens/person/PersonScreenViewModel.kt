package com.takeoutfm.tv.presentation.screens.person

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.tv.data.entities.PersonDetails
import com.takeoutfm.tv.data.repositories.TakeoutMovieRepository
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
class PersonScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: TakeoutMovieRepository,
) : ViewModel() {
    private val retries = MutableStateFlow(0)

    /** Loads again after a failure (the error screen's Try again). */
    fun retry() {
        retries.value++
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        savedStateHandle.getStateFlow<String?>(PersonScreen.PersonIdBundleKey, null),
        retries,
    ) { id, _ -> id }
        .transformLatest { id ->
            if (id == null) {
                emit(PersonScreenUiState.Error)
                return@transformLatest
            }
            emit(PersonScreenUiState.Loading)
            val state = try {
                PersonScreenUiState.Done(repository.getPersonDetails(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PersonScreenUiState.Error
            }
            emit(state)
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
