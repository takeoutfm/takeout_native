package com.takeoutfm.tv.presentation.screens.music.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.takeoutfm.tv.R
import com.takeoutfm.shared.music.OnNow
import com.takeoutfm.shared.music.RadioNow
import com.takeoutfm.shared.music.Station
import com.takeoutfm.shared.music.StationGroup
import com.takeoutfm.shared.music.StationKind
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.MusicHomeSkeleton
import com.takeoutfm.tv.presentation.common.TileRow
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.common.MusicMessage
import com.takeoutfm.tv.presentation.screens.music.common.MusicTabColumn
import com.takeoutfm.tv.presentation.utils.RestoreRememberedFocus
import com.takeoutfm.tv.presentation.utils.rememberFocusMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Radio stations, one row per kind (live, then streams); selecting one plays it. A live
 * station's card is the cover of what's on, with the station (and show) and "Artist – Title".
 */
@Composable
fun RadioScreen(
    onStationClick: (Station) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onNow by viewModel.radioNow.collectAsStateWithLifecycle(initialValue = null)
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
                            val on = onNow?.stations?.get(station.id)
                            if (on == null) {
                                MusicImage(
                                    url = station.imageUri,
                                    contentDescription = station.name,
                                    modifier = imageModifier.fillMaxWidth(),
                                    placeholderKey = station.id,
                                    fallbackText = station.name,
                                )
                            } else {
                                OnNowCard(station, on, imageModifier.fillMaxWidth())
                            }
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

/** A live station's card: what's on, its cover, and the station's name over it. */
@Composable
private fun OnNowCard(station: Station, on: OnNow, modifier: Modifier) {
    Box(modifier) {
        MusicImage(
            url = on.track.imageUri.ifEmpty { on.show?.imageUri.orEmpty() }.ifEmpty { station.imageUri },
            contentDescription = station.name,
            modifier = Modifier.fillMaxSize(),
            placeholderKey = station.id,
            fallbackText = station.name,
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                listOfNotNull(station.name, on.show?.name).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                on.line,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val StationKind.titleRes: Int
    get() = when (this) {
        StationKind.Live -> R.string.radio_live
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

    /**
     * What's on each live station, fetched again when the first of those tracks ends (or a
     * minute after a failure), while the screen is shown.
     */
    val radioNow: Flow<RadioNow?> = flow {
        while (true) {
            val now = try {
                repository.radioNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            emit(now)
            val wait = now?.refreshAtMs?.takeIf { it > 0 }?.let { it - System.currentTimeMillis() } ?: 60_000L
            delay(wait.coerceIn(2_000L, 10 * 60_000L))
        }
    }

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
