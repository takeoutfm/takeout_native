package com.takeoutfm.tv.presentation.screens.music.podcasts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.takeoutfm.shared.podcast.PodcastSeries
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.PosterGridSkeleton
import com.takeoutfm.tv.presentation.common.TileGrid
import com.takeoutfm.tv.presentation.theme.JetStreamButtonShape
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.common.MusicMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Podcasts tab: podcast covers, with Subscribed / All podcasts above them. Starts on
 * Subscribed when there are any; selecting a podcast opens its page.
 */
@Composable
fun PodcastsScreen(
    onPodcastClick: (PodcastSeries) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: PodcastsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from a podcast page: pick up a changed subscription.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    when (val s = state) {
        PodcastsState.Loading -> PosterGridSkeleton(Modifier.fillMaxSize(), aspectRatio = 1f)
        PodcastsState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is PodcastsState.Ready -> {
            val list = if (s.showSubscribed) s.subscribed else s.all
            TileGrid(
                items = list,
                key = { it.id },
                itemTitle = { it.title },
                itemImage = { series, imageModifier ->
                    MusicImage(
                        url = series.imageUri,
                        contentDescription = series.title,
                        modifier = imageModifier,
                        placeholderKey = series.id,
                        fallbackText = series.title,
                    )
                },
                onItemClick = onPodcastClick,
                aspectRatio = ItemDirection.Square.aspectRatio,
                onScroll = onScroll,
                isTopBarVisible = isTopBarVisible,
                modifier = Modifier.fillMaxSize(),
                header = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        ListChoice(
                            text = stringResource(R.string.podcasts_subscribed),
                            icon = Icons.Default.Check,
                            selected = s.showSubscribed,
                            onClick = { viewModel.show(subscribed = true) },
                        )
                        ListChoice(
                            text = stringResource(R.string.podcasts_all),
                            icon = Icons.Default.Podcasts,
                            selected = !s.showSubscribed,
                            onClick = { viewModel.show(subscribed = false) },
                        )
                    }
                },
            )
            if (list.isEmpty() && s.showSubscribed) MusicMessage(stringResource(R.string.podcasts_none_subscribed))
        }
    }
}

/**
 * Subscribed / All: filled when selected, outlined when not. One kind of button for both
 * (unlike [com.takeoutfm.tv.presentation.screens.music.common.MusicButton]), so the focused
 * one keeps focus when it becomes selected.
 */
@Composable
private fun ListChoice(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val outline = Border(
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.border),
        shape = JetStreamButtonShape,
    )
    Button(
        onClick = onClick,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        shape = ButtonDefaults.shape(shape = JetStreamButtonShape),
        colors = if (selected) {
            ButtonDefaults.colors()
        } else {
            ButtonDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
        border = ButtonDefaults.border(border = if (selected) Border.None else outline),
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(text = text, style = MaterialTheme.typography.titleSmall)
    }
}

sealed interface PodcastsState {
    data object Loading : PodcastsState
    data object Error : PodcastsState
    data class Ready(
        val subscribed: List<PodcastSeries>,
        val all: List<PodcastSeries>,
        val showSubscribed: Boolean,
    ) : PodcastsState
}

@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val podcasts: TakeoutPodcastRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<PodcastsState>(PodcastsState.Loading)
    val state: StateFlow<PodcastsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = PodcastsState.Loading
        viewModelScope.launch {
            _state.value = try {
                val subscribed = podcasts.series(subscribedOnly = true)
                val all = podcasts.series(subscribedOnly = false)
                PodcastsState.Ready(subscribed, all, showSubscribed = subscribed.isNotEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PodcastsState.Error
            }
        }
    }

    /** Reloads the lists quietly, keeping what's shown if it fails. */
    fun refresh() {
        if (_state.value !is PodcastsState.Ready) return
        viewModelScope.launch {
            runCatching { podcasts.series(subscribedOnly = true) }.onSuccess { subscribed ->
                (_state.value as? PodcastsState.Ready)?.let { _state.value = it.copy(subscribed = subscribed) }
            }
        }
    }

    fun show(subscribed: Boolean) {
        val s = _state.value as? PodcastsState.Ready ?: return
        _state.value = s.copy(showSubscribed = subscribed)
    }
}
