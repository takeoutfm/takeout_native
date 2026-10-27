package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.takeoutfm.media.offline.CacheStatus
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.main.MainViewModel
import com.takeoutfm.mobile.ui.main.PodcastEpisodeRoute
import com.takeoutfm.shared.podcast.EpisodeProgress
import com.takeoutfm.shared.podcast.PodcastDetails
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.progress.TakeoutProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PodcastEpisodeViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val podcasts: TakeoutPodcastRepository,
    private val progress: TakeoutProgress,
) : ViewModel() {
    private val route = saved.toRoute<PodcastEpisodeRoute>()
    val episodeId = route.episodeId
    private val _state = MutableStateFlow<Load<PodcastDetails>>(Load.Loading)

    /** The episode's podcast, with every episode (for the neighbours and the play queue). */
    val state: StateFlow<Load<PodcastDetails>> = _state.asStateFlow()

    /** (Re)loads, e.g. back from playing, so its progress is current. */
    fun load() {
        viewModelScope.launch {
            val loaded = try {
                Load.Loaded(podcasts.details(route.seriesId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
            // A failed refresh keeps what's shown.
            if (loaded is Load.Loaded || _state.value !is Load.Loaded) _state.value = loaded
        }
    }

    /** Marks it played (Complete) or not (back to the start); changes the user's real progress. */
    fun setPlayed(played: Boolean) {
        val episode = (_state.value as? Load.Loaded)?.value?.episodes?.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            val (offset, duration) = TakeoutPodcastRepository.playedMark(podcasts.offset(episode.eid), played)
            progress.report(episode.eid, offset * 1000L, duration * 1000L)
            load()
        }
    }
}

/**
 * A podcast episode: its art, title, podcast (a link back), date and progress; Play / Resume and
 * Start over, Download, Mark as played; the show notes (links open in the browser); then the
 * previous (older) and next (newer) episode.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PodcastEpisodeScreen(main: MainViewModel, nav: MusicNav, viewModel: PodcastEpisodeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by main.downloads.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }
    val details = (state as? Load.Loaded)?.value
    val index = details?.episodes?.indexOfFirst { it.id == viewModel.episodeId } ?: -1
    MusicPage(title = details?.series?.title.orEmpty(), onBack = nav.back) { padding ->
        if (details == null || index < 0) {
            LoadingOrEmpty(state is Load.Loading, stringResource(R.string.cant_load), Modifier.padding(padding))
            return@MusicPage
        }
        val series = details.series
        val episode = details.episodes[index]
        val started = episode.progress as? EpisodeProgress.Started
        val play = { fromStart: Boolean -> main.playEpisodes(series.title, series.imageUri, details.episodes, index, fromStart) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Art(episode.imageUri.ifEmpty { series.imageUri }, episode.title, Modifier.size(120.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(episode.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        series.title + " ›",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { nav.openPodcast(series.id) },
                    )
                    Text(
                        listOfNotNull(published(episode.date), progressLabel(episode.progress)).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (started != null && started.fraction > 0f) {
                LinearProgressIndicator(progress = { started.fraction }, modifier = Modifier.fillMaxWidth())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { play(false) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(if (started != null) R.string.resume else R.string.play), modifier = Modifier.padding(start = 6.dp))
                }
                if (started != null) {
                    FilledTonalButton(onClick = { play(true) }) {
                        Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.play_from_start), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                when (val status = downloads[episode.eid]?.status) {
                    null -> OutlinedButton(onClick = { main.downloadEpisode(series.title, series.imageUri, details.episodes, index) }) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.download), modifier = Modifier.padding(start = 6.dp))
                    }
                    else -> OutlinedButton(onClick = {}, enabled = false) {
                        if (status == CacheStatus.Cached) {
                            Icon(Icons.Filled.DownloadDone, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.downloaded), modifier = Modifier.padding(start = 6.dp))
                        } else {
                            DownloadBadge(status)
                        }
                    }
                }
                val played = episode.progress == EpisodeProgress.Complete
                OutlinedButton(onClick = { viewModel.setPlayed(!played) }) {
                    Icon(
                        if (played) Icons.Filled.RadioButtonUnchecked else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(if (played) R.string.mark_unplayed else R.string.mark_played),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (episode.description.isNotBlank()) {
                Text(
                    showNotes(episode.description),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Episodes are listed newest first: the previous one is older.
            val older = details.episodes.getOrNull(index + 1)
            val newer = details.episodes.getOrNull(index - 1)
            if (older != null || newer != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (older != null) {
                        OutlinedButton(onClick = { nav.openEpisode(series.id, older.id) }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.previous_episode), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    if (newer != null) {
                        OutlinedButton(onClick = { nav.openEpisode(series.id, newer.id) }) {
                            Text(stringResource(R.string.next_episode), modifier = Modifier.padding(end = 6.dp))
                            Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(96.dp)) // clear of the play button
        }
    }
}

/** Show notes from the feed's HTML, paragraphs kept; links are tappable (they open the browser). */
@Composable
private fun showNotes(html: String): AnnotatedString {
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    // Plain-text notes keep their line breaks; HTML ones are parsed.
    val source = if (html.contains('<')) html else html.replace("\n", "<br>")
    return AnnotatedString.fromHtml(source, linkStyles = link)
}
