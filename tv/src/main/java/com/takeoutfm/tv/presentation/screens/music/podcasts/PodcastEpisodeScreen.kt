package com.takeoutfm.tv.presentation.screens.music.podcasts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.takeoutfm.shared.podcast.EpisodeProgress
import com.takeoutfm.shared.podcast.PodcastDetails
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.common.DetailsSkeleton
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.screens.music.common.MusicButton
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.player.QueueRef
import com.takeoutfm.tv.presentation.theme.JetStreamButtonShape
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import com.takeoutfm.tv.presentation.utils.RestoreRememberedFocus
import com.takeoutfm.tv.presentation.utils.focusOnInitialVisibility
import com.takeoutfm.tv.presentation.utils.rememberFocus
import com.takeoutfm.tv.presentation.utils.rememberFocusMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A podcast episode: art, title, podcast, date and progress; Play / Resume, Start over, Mark as
 * played, Previous (older) / Next (newer); then the show notes, one focusable paragraph at a
 * time so the remote scrolls through them.
 */
@Composable
fun PodcastEpisodeScreen(
    onPlay: (QueueRef) -> Unit,
    onOpenEpisode: (seriesId: String, episodeId: String) -> Unit,
    onBackPressed: () -> Unit,
    viewModel: PodcastEpisodeViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackPressed)
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from the player: show the new progress.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    when (val s = state) {
        PodcastState.Loading -> DetailsSkeleton(Modifier.fillMaxSize())
        PodcastState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        is PodcastState.Ready -> {
            val index = s.details.episodes.indexOfFirst { it.id == viewModel.episodeId }
            if (index < 0) {
                Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
            } else {
                EpisodeContent(
                    details = s.details,
                    index = index,
                    focusedKey = viewModel.focusedKey,
                    onPlay = onPlay,
                    onOpenEpisode = onOpenEpisode,
                    onSetPlayed = viewModel::setPlayed,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeContent(
    details: PodcastDetails,
    index: Int,
    focusedKey: androidx.compose.runtime.MutableState<String?>,
    onPlay: (QueueRef) -> Unit,
    onOpenEpisode: (seriesId: String, episodeId: String) -> Unit,
    onSetPlayed: (Boolean) -> Unit,
) {
    val childPadding = rememberChildPadding()
    val focusMemory = rememberFocusMemory(focusedKey)
    RestoreRememberedFocus(focusMemory)
    val series = details.series
    val episode = details.episodes[index]
    val started = episode.progress as? EpisodeProgress.Started
    // Focusing a button scrolls the whole header into view, not just the button.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bringHeaderIntoView = Modifier.onFocusChanged {
        if (it.isFocused) scope.launch { bringIntoView.bringIntoView() }
    }
    val paragraphs = remember(episode.description) { paragraphs(episode.description) }
    // Play takes focus when the page opens (once it's laid out).
    val playFocused = remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(top = 48.dp, bottom = 108.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoView)
                    .padding(horizontal = childPadding.start),
            ) {
                MusicImage(
                    url = episode.imageUri.ifEmpty { series.imageUri },
                    contentDescription = episode.title,
                    modifier = Modifier
                        .size(240.dp)
                        .clip(JetStreamCardShape),
                    placeholderKey = episode.id,
                    fallbackText = series.title,
                )
                Column(Modifier.padding(start = 32.dp)) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = series.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = listOfNotNull(published(episode.date), progressLabel(episode.progress))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (started != null && started.fraction > 0f) {
                        ProgressBar(started.fraction, Modifier.padding(top = 12.dp))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(top = 24.dp),
                    ) {
                        MusicButton(
                            modifier = bringHeaderIntoView
                                .rememberFocus(focusMemory, "play")
                                .focusOnInitialVisibility(playFocused),
                            text = stringResource(if (started != null) R.string.podcast_resume else R.string.music_play),
                            icon = Icons.Outlined.PlayArrow,
                            primary = true,
                            onClick = { onPlay(QueueRef.Episodes(series.id, index)) },
                        )
                        if (started != null) {
                            MusicButton(
                                modifier = bringHeaderIntoView,
                                text = stringResource(R.string.start_over),
                                icon = Icons.Outlined.Replay,
                                onClick = {
                                    focusMemory.lastKey.value = null
                                    onPlay(QueueRef.Episodes(series.id, index, fromStart = true))
                                },
                            )
                        }
                        val played = episode.progress == EpisodeProgress.Complete
                        MusicButton(
                            modifier = bringHeaderIntoView,
                            text = stringResource(if (played) R.string.podcast_mark_unplayed else R.string.podcast_mark_played),
                            icon = if (played) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle,
                            onClick = { onSetPlayed(!played) },
                        )
                        // Episodes are listed newest first: the previous one is older.
                        details.episodes.getOrNull(index + 1)?.let { older ->
                            MusicButton(
                                modifier = bringHeaderIntoView,
                                text = stringResource(R.string.previous_episode),
                                icon = Icons.Outlined.SkipPrevious,
                                onClick = { onOpenEpisode(series.id, older.id) },
                            )
                        }
                        details.episodes.getOrNull(index - 1)?.let { newer ->
                            MusicButton(
                                modifier = bringHeaderIntoView,
                                text = stringResource(R.string.next_episode),
                                icon = Icons.Outlined.SkipNext,
                                onClick = { onOpenEpisode(series.id, newer.id) },
                            )
                        }
                    }
                }
            }
        }
        itemsIndexed(paragraphs, key = { i, _ -> "notes-$i" }) { i, paragraph ->
            ShowNotesParagraph(
                text = paragraph,
                modifier = Modifier
                    .padding(horizontal = childPadding.start)
                    .padding(top = if (i == 0) 32.dp else 4.dp)
                    .rememberFocus(focusMemory, "notes-$i"),
            )
        }
    }
}

/** A paragraph of show notes; focusable only so the D-pad can scroll the page through them. */
@Composable
private fun ShowNotesParagraph(text: String, modifier: Modifier = Modifier) {
    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(JetStreamButtonShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.width(960.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** The show notes as plain-text paragraphs (the feed's HTML, links as their text). */
private fun paragraphs(html: String): List<String> {
    val source = if (html.contains('<')) html else html.replace("\n", "<br>")
    return HtmlCompat.fromHtml(source, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        .split(Regex("\n\\s*\n"))
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
}

@HiltViewModel
class PodcastEpisodeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val podcasts: TakeoutPodcastRepository,
    private val progress: TakeoutProgress,
) : ViewModel() {
    private val seriesId: String = savedStateHandle[PodcastScreen.SeriesIdBundleKey] ?: ""
    val episodeId: String = savedStateHandle[PodcastScreen.EpisodeIdBundleKey] ?: ""

    private val _state = MutableStateFlow<PodcastState>(PodcastState.Loading)
    val state: StateFlow<PodcastState> = _state.asStateFlow()

    /** The button or paragraph that last had focus, so Back from the player returns to it. */
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

    /** Reloads the progress quietly, keeping what's shown if it fails. */
    fun refresh() {
        if (_state.value !is PodcastState.Ready) return
        viewModelScope.launch {
            runCatching { podcasts.details(seriesId) }.onSuccess { _state.value = PodcastState.Ready(it) }
        }
    }

    /** Marks it played (Complete) or not (back to the start); changes the user's real progress. */
    fun setPlayed(played: Boolean) {
        val details = (_state.value as? PodcastState.Ready)?.details ?: return
        val episode = details.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            val (offset, duration) = TakeoutPodcastRepository.playedMark(podcasts.offset(episode.eid), played)
            progress.report(episode.eid, offset * 1000L, duration * 1000L)
            refresh()
        }
    }
}
