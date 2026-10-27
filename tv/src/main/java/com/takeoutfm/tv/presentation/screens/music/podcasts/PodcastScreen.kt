package com.takeoutfm.tv.presentation.screens.music.podcasts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.takeoutfm.shared.podcast.EpisodeProgress
import com.takeoutfm.shared.podcast.PodcastDetails
import com.takeoutfm.shared.podcast.PodcastEpisode
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.common.DetailsSkeleton
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.screens.music.common.MusicButton
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.player.QueueRef
import com.takeoutfm.tv.presentation.theme.JetStreamButtonShape
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import com.takeoutfm.tv.presentation.utils.FocusMemory
import com.takeoutfm.tv.presentation.utils.RestoreRememberedFocus
import com.takeoutfm.tv.presentation.utils.rememberFocus
import com.takeoutfm.tv.presentation.utils.rememberFocusMemory
import com.takeoutfm.tv.presentation.utils.returnFocusTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object PodcastScreen {
    const val SeriesIdBundleKey = "seriesId"
}

/**
 * A podcast: cover, author, description, Play (the newest episode) and Subscribe, then its
 * episodes with date and progress; selecting one plays the episodes from there, resuming it
 * if it was started.
 */
@Composable
fun PodcastScreen(
    onPlay: (QueueRef) -> Unit,
    onBackPressed: () -> Unit,
    viewModel: PodcastScreenViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackPressed)
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from the player: show the new progress.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    when (val s = state) {
        PodcastState.Loading -> DetailsSkeleton(Modifier.fillMaxSize())
        PodcastState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is PodcastState.Ready -> {
            val focusMemory = rememberFocusMemory(viewModel.focusedKey)
            RestoreRememberedFocus(focusMemory)
            PodcastContent(
                details = s.details,
                focusMemory = focusMemory,
                onPlay = onPlay,
                onSubscribe = viewModel::setSubscribed,
            )
        }
    }
}

@Composable
private fun PodcastContent(
    details: PodcastDetails,
    focusMemory: FocusMemory,
    onPlay: (QueueRef) -> Unit,
    onSubscribe: (Boolean) -> Unit,
) {
    val seriesId = details.series.id
    val childPadding = rememberChildPadding()
    LazyColumn(
        contentPadding = PaddingValues(bottom = 108.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            PodcastHeader(
                details = details,
                onPlay = {
                    focusMemory.lastKey.value = null
                    onPlay(QueueRef.Episodes(seriesId))
                },
                onSubscribe = onSubscribe,
            )
        }
        itemsIndexed(details.episodes, key = { _, e -> e.id }) { index, episode ->
            EpisodeListItem(
                episode = episode,
                onClick = { onPlay(QueueRef.Episodes(seriesId, index)) },
                modifier = Modifier
                    .padding(horizontal = childPadding.start)
                    .rememberFocus(focusMemory, episode.id)
                    .returnFocusTarget(),
            )
        }
    }
}

/** One episode: title, then its date and progress ("12 min left", Played) and a short summary. */
@Composable
private fun EpisodeListItem(
    episode: PodcastEpisode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(JetStreamButtonShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val facts = listOfNotNull(published(episode.date), progressLabel(episode.progress))
                val summary = plain(episode.description)
                Text(
                    text = (facts + listOfNotNull(summary.ifEmpty { null })).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    // Dimmer than the title, and dark on the focused (light) row.
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val progress = episode.progress
                if (progress is EpisodeProgress.Started && progress.fraction > 0f) {
                    ProgressBar(progress.fraction, Modifier.padding(top = 8.dp))
                }
            }
            // A fixed slot, so titles line up whether or not an episode is played.
            Box(Modifier.padding(start = 24.dp).size(28.dp), contentAlignment = Alignment.Center) {
                if (episode.progress == EpisodeProgress.Complete) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.podcast_played),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/** How far into an episode: a short bar in the row's content colour (dark when focused). */
@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Box(
        modifier
            .width(240.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastHeader(
    details: PodcastDetails,
    onPlay: () -> Unit,
    onSubscribe: (Boolean) -> Unit,
) {
    val childPadding = rememberChildPadding()
    val series = details.series
    // Focusing a button scrolls the whole header into view, not just the button.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bringHeaderIntoView = Modifier.onFocusChanged {
        if (it.isFocused) scope.launch { bringIntoView.bringIntoView() }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
            .bringIntoViewRequester(bringIntoView)
            .padding(horizontal = childPadding.start),
    ) {
        MusicImage(
            url = series.imageUri,
            contentDescription = series.title,
            modifier = Modifier
                .size(260.dp)
                .clip(JetStreamCardShape),
            placeholderKey = series.id,
            fallbackText = series.title,
        )
        Column(Modifier.padding(start = 32.dp)) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.displaySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (series.author.isNotEmpty()) {
                Text(
                    text = series.author,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val description = plain(series.description)
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .width(640.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                if (details.episodes.isNotEmpty()) {
                    MusicButton(
                        modifier = bringHeaderIntoView,
                        text = stringResource(R.string.music_play),
                        icon = Icons.Outlined.PlayArrow,
                        primary = true,
                        onClick = onPlay,
                    )
                }
                MusicButton(
                    modifier = bringHeaderIntoView,
                    text = stringResource(
                        if (details.subscribed) R.string.podcast_subscribed else R.string.podcast_subscribe
                    ),
                    icon = if (details.subscribed) Icons.Default.Check else Icons.Default.Add,
                    onClick = { onSubscribe(!details.subscribed) },
                )
            }
        }
    }
}

@Composable
private fun progressLabel(p: EpisodeProgress): String? = when (p) {
    EpisodeProgress.NotStarted -> null
    EpisodeProgress.Complete -> stringResource(R.string.podcast_played)
    is EpisodeProgress.Started ->
        if (p.minutesLeft > 0) stringResource(R.string.podcast_minutes_left, p.minutesLeft) else null
}

/** "Oct 21, 2026" for an ISO date; null when unknown. */
private fun published(date: String): String? =
    runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()?.takeIf { it > 0 }
        ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }

/** Podcast descriptions are often HTML; show them as plain text. */
private fun plain(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        .replace(Regex("\\s+"), " ")
        .trim()

sealed interface PodcastState {
    data object Loading : PodcastState
    data object Error : PodcastState
    data class Ready(val details: PodcastDetails) : PodcastState
}

@HiltViewModel
class PodcastScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val podcasts: TakeoutPodcastRepository,
) : ViewModel() {
    private val seriesId: String = savedStateHandle[PodcastScreen.SeriesIdBundleKey] ?: ""

    private val _state = MutableStateFlow<PodcastState>(PodcastState.Loading)
    val state: StateFlow<PodcastState> = _state.asStateFlow()

    /** The episode or button that last had focus, so Back from the player returns to it. */
    val focusedKey = mutableStateOf<String?>(null)

    init {
        load()
    }

    fun load() {
        _state.value = PodcastState.Loading
        viewModelScope.launch {
            _state.value = try {
                PodcastState.Ready(podcasts.details(seriesId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PodcastState.Error
            }
        }
    }

    /** Reloads the episodes' progress quietly, keeping what's shown if it fails. */
    fun refresh() {
        if (_state.value !is PodcastState.Ready) return
        viewModelScope.launch {
            runCatching { podcasts.details(seriesId) }.onSuccess { _state.value = PodcastState.Ready(it) }
        }
    }

    fun setSubscribed(subscribed: Boolean) {
        val details = (_state.value as? PodcastState.Ready)?.details ?: return
        _state.value = PodcastState.Ready(details.copy(subscribed = subscribed))
        viewModelScope.launch {
            runCatching { podcasts.setSubscribed(seriesId, subscribed) }
                .onFailure { _state.value = PodcastState.Ready(details) }
        }
    }
}
