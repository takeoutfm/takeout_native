package com.google.jetstream.presentation.screens.music.radio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import com.google.jetstream.R
import com.google.jetstream.data.entities.music.Station
import com.google.jetstream.data.entities.music.StationGroup
import com.google.jetstream.data.entities.music.StationKind
import com.google.jetstream.data.music.TakeoutMusicRepository
import com.google.jetstream.presentation.common.Error
import com.google.jetstream.presentation.common.ItemDirection
import com.google.jetstream.presentation.common.MusicHomeSkeleton
import com.google.jetstream.presentation.common.TileRow
import com.google.jetstream.presentation.screens.music.common.MusicImage
import com.google.jetstream.presentation.screens.music.common.MusicMessage
import com.google.jetstream.presentation.screens.music.common.MusicTabColumn
import com.google.jetstream.presentation.utils.RestoreRememberedFocus
import com.google.jetstream.presentation.utils.rememberFocusMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The user's radio stations, one row per kind; selecting one plays it. */
@Composable
fun RadioScreen(
    onStationClick: (Station) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        RadioState.Loading -> MusicHomeSkeleton(Modifier.fillMaxSize())
        RadioState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is RadioState.Ready -> if (s.groups.isEmpty()) {
            MusicMessage(stringResource(R.string.music_no_stations))
        } else {
            val focusMemory = rememberFocusMemory(viewModel.focusedKey)
            RestoreRememberedFocus(focusMemory)
            MusicTabColumn(onScroll = onScroll, isTopBarVisible = isTopBarVisible) {
                items(s.groups, key = { it.kind.name }) { group ->
                    TileRow(
                        items = group.stations,
                        key = { it.id },
                        itemTitle = { it.name },
                        itemImage = { station, _, imageModifier ->
                            MusicImage(
                                url = station.imageUri,
                                contentDescription = station.name,
                                modifier = imageModifier.fillMaxWidth(),
                                placeholderKey = station.id,
                                fallbackText = station.name,
                            )
                        },
                        modifier = Modifier.padding(top = 16.dp),
                        itemDirection = ItemDirection.Square,
                        title = stringResource(group.kind.titleRes),
                        titleStyle = MaterialTheme.typography.titleMedium,
                        onItemSelected = onStationClick,
                        focusMemory = focusMemory,
                    )
                }
            }
        }
    }
}

private val StationKind.titleRes: Int
    get() = when (this) {
        StationKind.Genre -> R.string.radio_genre
        StationKind.Period -> R.string.radio_period
        StationKind.Artist -> R.string.radio_artist
        StationKind.Similar -> R.string.radio_similar
        StationKind.Series -> R.string.radio_series
        StationKind.Other -> R.string.radio_other
        StationKind.Stream -> R.string.radio_stream
    }

sealed interface RadioState {
    data object Loading : RadioState
    data object Error : RadioState
    data class Ready(val groups: List<StationGroup>) : RadioState
}

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<RadioState>(RadioState.Loading)
    val state: StateFlow<RadioState> = _state.asStateFlow()

    /** The station that last had focus, so Back from the player returns to it. */
    val focusedKey = mutableStateOf<String?>(null)

    init {
        load()
    }

    fun load() {
        _state.value = RadioState.Loading
        viewModelScope.launch {
            _state.value = try {
                RadioState.Ready(repository.radio())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RadioState.Error
            }
        }
    }
}
