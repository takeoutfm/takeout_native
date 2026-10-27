package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import androidx.navigation.toRoute
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.main.MainViewModel
import com.takeoutfm.mobile.ui.main.PodcastRoute
import com.takeoutfm.shared.podcast.EpisodeProgress
import com.takeoutfm.shared.podcast.PodcastDetails
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

@HiltViewModel
class PodcastViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val podcasts: TakeoutPodcastRepository,
) : ViewModel() {
    private val id = saved.toRoute<PodcastRoute>().id
    private val _state = MutableStateFlow<Load<PodcastDetails>>(Load.Loading)
    val state: StateFlow<Load<PodcastDetails>> = _state.asStateFlow()

    /** (Re)loads, e.g. back from playing, so episode progress is current. */
    fun load() {
        viewModelScope.launch {
            val loaded = try {
                Load.Loaded(podcasts.details(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
            // A failed refresh keeps what's shown.
            if (loaded is Load.Loaded || _state.value !is Load.Loaded) _state.value = loaded
        }
    }

    fun setSubscribed(subscribed: Boolean) {
        val details = (_state.value as? Load.Loaded)?.value ?: return
        _state.value = Load.Loaded(details.copy(subscribed = subscribed))
        viewModelScope.launch {
            runCatching { podcasts.setSubscribed(id, subscribed) }.onFailure { _state.value = Load.Loaded(details) }
        }
    }
}

/** A podcast: cover, author, Subscribe, description, then episodes with their progress. */
@Composable
fun PodcastScreen(main: MainViewModel, nav: MusicNav, viewModel: PodcastViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by main.downloads.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }
    val details = (state as? Load.Loaded)?.value
    MusicPage(title = details?.series?.title.orEmpty(), onBack = nav.back) { padding ->
        if (details == null) {
            LoadingOrEmpty(state is Load.Loading, stringResource(R.string.cant_load), Modifier.padding(padding))
            return@MusicPage
        }
        val series = details.series
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Art(series.imageUri, series.title, Modifier.size(140.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(series.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (series.author.isNotEmpty()) Text(series.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (details.subscribed) {
                            OutlinedButton(onClick = { viewModel.setSubscribed(false) }) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.subscribed), modifier = Modifier.padding(start = 6.dp))
                            }
                        } else {
                            FilledTonalButton(onClick = { viewModel.setSubscribed(true) }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.subscribe), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                val description = plain(series.description)
                if (description.isNotEmpty()) {
                    Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                }
                SectionTitle(stringResource(R.string.episodes))
            }
            itemsIndexed(details.episodes, key = { _, e -> e.id }) { index, e ->
                ListItem(
                    headlineContent = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val summary = plain(e.description)
                            if (summary.isNotEmpty()) Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(published(e.date), progressLabel(e.progress)).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            (e.progress as? EpisodeProgress.Started)?.takeIf { it.fraction > 0f }?.let {
                                LinearProgressIndicator(progress = { it.fraction }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (e.progress == EpisodeProgress.Complete) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.complete), tint = MaterialTheme.colorScheme.primary)
                            }
                            downloads[e.eid]?.status?.let { DownloadBadge(it, Modifier.padding(start = 8.dp)) }
                            // Plays from here (resuming it if started); the row opens its page,
                            // where Download is.
                            androidx.compose.material3.IconButton(onClick = { main.playEpisodes(series.title, series.imageUri, details.episodes, index) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play))
                            }
                        }
                    },
                    leadingContent = if (e.imageUri.isNotEmpty() && e.imageUri != series.imageUri) {
                        { Art(e.imageUri, e.title, Modifier.size(56.dp), RoundedCornerShape(6.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable { nav.openEpisode(series.id, e.id) },
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
internal fun progressLabel(p: EpisodeProgress): String? = when (p) {
    EpisodeProgress.NotStarted -> null
    EpisodeProgress.Complete -> stringResource(R.string.complete)
    is EpisodeProgress.Started -> if (p.minutesLeft > 0) stringResource(R.string.minutes_left, p.minutesLeft) else null
}

/** "3 days ago" for an ISO date; null when unknown. */
internal fun published(date: String): String? =
    runCatching { Instant.parse(date).toEpochMilliseconds() }.getOrNull()?.takeIf { it > 0 }?.let(::ago)

/** Podcast descriptions are often HTML; show them as plain text. */
internal fun plain(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim()
