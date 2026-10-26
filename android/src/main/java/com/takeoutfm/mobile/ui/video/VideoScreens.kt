package com.takeoutfm.mobile.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.takeoutfm.mobile.ui.music.headerSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil.compose.AsyncImage
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.common.Tile
import com.takeoutfm.mobile.ui.common.TileGrid
import com.takeoutfm.mobile.ui.main.GenreRoute
import com.takeoutfm.mobile.ui.main.PersonRoute
import com.takeoutfm.mobile.ui.main.VideoRoute
import com.takeoutfm.mobile.ui.music.Load
import com.takeoutfm.mobile.ui.music.MusicPage
import com.takeoutfm.mobile.ui.music.SectionTitle
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.video.CreditKind
import com.takeoutfm.shared.video.GenreMovies
import com.takeoutfm.shared.video.PersonInfo
import com.takeoutfm.shared.video.TakeoutVideoRepository
import com.takeoutfm.shared.video.VideoCard
import com.takeoutfm.shared.video.VideoDetails
import com.takeoutfm.shared.video.VideoKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the video pages can go. */
class VideoNav(
    val openVideo: (String) -> Unit,
    val openPerson: (String) -> Unit,
    val openGenre: (String) -> Unit,
    val back: () -> Unit,
)

private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Loaded(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Load.Failed
}

@HiltViewModel
class VideoViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val video: TakeoutVideoRepository,
    private val progress: TakeoutProgress,
) : ViewModel() {
    val id = saved.toRoute<VideoRoute>().id
    private val _state = MutableStateFlow<Load<VideoDetails>>(Load.Loading)
    val state: StateFlow<Load<VideoDetails>> = _state.asStateFlow()

    /** Saved progress for the title Play starts, when it's worth resuming. */
    private val _offset = MutableStateFlow<ApiOffset?>(null)
    val offset: StateFlow<ApiOffset?> = _offset.asStateFlow()

    init {
        viewModelScope.launch { _state.value = load { video.details(id) } }
    }

    /** Back from the player: the series' next episode and the resume point may have moved. */
    fun refresh() {
        viewModelScope.launch {
            val details = (_state.value as? Load.Loaded)?.value
            if (details != null && details.kind == VideoKind.Series) {
                (load { video.details(id) } as? Load.Loaded)?.let { _state.value = it }
            }
            val etag = (_state.value as? Load.Loaded)?.value?.etag.orEmpty()
            _offset.value = if (etag.isEmpty()) null else runCatching {
                progress.latest()[etag]?.takeIf { ProgressRules.shouldResume(it.offset, it.duration) }
            }.getOrNull()
        }
    }

    fun setFavorite(favorite: Boolean) {
        val details = (_state.value as? Load.Loaded)?.value ?: return
        val path = details.favoritePath ?: return
        _state.value = Load.Loaded(details.copy(isFavorite = favorite))
        viewModelScope.launch {
            runCatching { video.setFavorite(path, favorite) }.onFailure { _state.value = Load.Loaded(details) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/**
 * A movie, series or episode: backdrop, facts, Resume / Play from start, genres, cast & crew
 * (directors first), seasons with their episodes, related titles.
 */
@Composable
fun VideoScreen(nav: VideoNav, viewModel: VideoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offset by viewModel.offset.collectAsStateWithLifecycle()
    val details = (state as? Load.Loaded)?.value
    val context = LocalContext.current
    LaunchedEffect(details?.etag) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    MusicPage(
        title = details?.title.orEmpty(),
        onBack = nav.back,
        actions = {
            if (details?.favoritePath != null) {
                IconButton(onClick = { viewModel.setFavorite(!details.isFavorite) }) {
                    Icon(
                        if (details.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(if (details.isFavorite) R.string.unfavorite else R.string.favorite),
                    )
                }
            }
        },
    ) { padding ->
        if (details == null) {
            LoadingOrEmpty(state is Load.Loading, stringResource(R.string.cant_load), Modifier.padding(padding))
            return@MusicPage
        }
        val play = { startOver: Boolean ->
            // A series plays its next episode (by the series page's pick).
            VideoPlayerActivity.start(context, details.id, startOver)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Backdrop(details)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(details.title, style = MaterialTheme.typography.headlineSmall)
                    Text(facts(details), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (details.tagline.isNotEmpty() && details.kind != VideoKind.Episode) {
                        Text(details.tagline, style = MaterialTheme.typography.bodyMedium, fontStyleItalic = true)
                    }
                    PlayButtons(details, offset, play)
                    if (details.description.isNotEmpty()) Text(details.description, style = MaterialTheme.typography.bodyMedium)
                    if (details.genres.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            details.genres.forEach { g ->
                                AssistChip(onClick = { nav.openGenre(TakeoutVideoRepository.slug(g)) }, label = { Text(g) })
                            }
                        }
                    }
                }
            }
            if (details.cast.isNotEmpty()) {
                item {
                    SectionTitle(stringResource(R.string.cast_crew))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(details.cast, key = { it.personId }) { c ->
                            Column(Modifier.width(96.dp).clickable { nav.openPerson(c.personId) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Art(c.avatarUri, c.name, Modifier.size(80.dp), RoundedCornerShape(50))
                                // Names always take two lines so every role starts at the same height.
                                Text(c.name, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                // Dimmer than the name, as on the TV.
                                Text(c.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            details.seasons.forEach { season ->
                item(key = "season-${season.number}") {
                    SectionTitle(if (season.number == 0) stringResource(R.string.specials) else stringResource(R.string.season, season.number))
                }
                items(season.episodes, key = { it.id }) { e ->
                    ListItem(
                        leadingContent = { Art(e.stillUri, e.name, Modifier.width(112.dp).aspectRatio(16f / 9f), RoundedCornerShape(6.dp)) },
                        headlineContent = { Text("${e.episode}. ${e.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { if (e.runtimeMinutes > 0) Text(stringResource(R.string.runtime_minutes, e.runtimeMinutes)) },
                        modifier = Modifier.clickable { nav.openVideo(e.id) },
                    )
                }
            }
            item {
                CardShelf(stringResource(R.string.related), details.similar) { nav.openVideo(it.id) }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun Text(text: String, style: androidx.compose.ui.text.TextStyle, fontStyleItalic: Boolean) =
    Text(text, style = style.copy(fontStyle = if (fontStyleItalic) androidx.compose.ui.text.font.FontStyle.Italic else null))

@Composable
private fun facts(d: VideoDetails): String = listOfNotNull(
    d.year.takeIf { it.isNotEmpty() && it != "0001" },
    d.rating.takeIf { it.isNotEmpty() },
    when {
        d.kind == VideoKind.Series -> stringResource(R.string.seasons_episodes, d.seasonCount, d.episodeCount)
        d.runtimeMinutes > 0 -> stringResource(R.string.runtime, d.runtimeMinutes / 60, d.runtimeMinutes % 60)
        else -> null
    },
    d.voteAverage?.let { stringResource(R.string.rating_tmdb, it) },
    d.directors.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString(" · ")

@Composable
private fun Backdrop(details: VideoDetails) {
    Box(
        Modifier
            .headerSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (details.backdropUri.isNotEmpty()) {
            AsyncImage(details.backdropUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))))
    }
}

/** Resume ("N min left") and Play from start, or just Play. */
@Composable
private fun PlayButtons(details: VideoDetails, offset: ApiOffset?, play: (startOver: Boolean) -> Unit) {
    if (details.videoUri.isEmpty()) {
        Text(stringResource(R.string.no_playable_file), color = MaterialTheme.colorScheme.error)
        return
    }
    val episode = details.playTarget?.let { " $it" }.orEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (offset != null) {
            Button(onClick = { play(false) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.resume) + episode, modifier = Modifier.padding(start = 6.dp))
            }
            FilledTonalButton(onClick = { play(true) }) {
                Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.play_from_start), modifier = Modifier.padding(start = 6.dp))
            }
        } else {
            Button(onClick = { play(true) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.play) + episode, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
    if (offset != null && offset.duration > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(progress = { offset.offset.toFloat() / offset.duration }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.minutes_left, ((offset.duration - offset.offset) / 60).coerceAtLeast(1)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A row of posters. */
@Composable
fun CardShelf(title: String, cards: List<VideoCard>, onClick: (VideoCard) -> Unit) {
    if (cards.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards, key = { it.id }) { card ->
            Column(Modifier.width(112.dp).clickable { onClick(card) }) {
                Art(card.posterUri, card.title, Modifier.width(112.dp).height(168.dp))
                Text(card.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@HiltViewModel
class PersonViewModel @Inject constructor(saved: SavedStateHandle, video: TakeoutVideoRepository) : ViewModel() {
    private val id = saved.toRoute<PersonRoute>().id
    private val _state = MutableStateFlow<Load<PersonInfo>>(Load.Loading)
    val state: StateFlow<Load<PersonInfo>> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = load { video.person(id) } }
    }
}

/** A person: photo, life summary, bio, then starring / directing / writing for movies and TV. */
@Composable
fun PersonScreen(nav: VideoNav, viewModel: PersonViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val person = (state as? Load.Loaded)?.value
    MusicPage(title = person?.name.orEmpty(), onBack = nav.back) { padding ->
        if (person == null) {
            LoadingOrEmpty(state is Load.Loading, stringResource(R.string.cant_load), Modifier.padding(padding))
            return@MusicPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Art(person.photoUri, person.name, Modifier.width(120.dp).height(180.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(person.name, style = MaterialTheme.typography.headlineSmall)
                        if (person.lifeSummary.isNotEmpty()) Text(person.lifeSummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (person.bio.isNotEmpty()) {
                    Text(person.bio, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            items(person.credits, key = { it.kind }) { group ->
                CardShelf(stringResource(group.kind.label()), group.titles) { nav.openVideo(it.id) }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

private fun CreditKind.label(): Int = when (this) {
    CreditKind.MoviesActing -> R.string.starring
    CreditKind.MoviesDirecting -> R.string.directing
    CreditKind.MoviesWriting -> R.string.writing
    CreditKind.ShowsActing -> R.string.starring_shows
    CreditKind.ShowsDirecting -> R.string.directing_shows
    CreditKind.ShowsWriting -> R.string.writing_shows
}

@HiltViewModel
class GenreViewModel @Inject constructor(saved: SavedStateHandle, video: TakeoutVideoRepository) : ViewModel() {
    private val id = saved.toRoute<GenreRoute>().id
    private val _state = MutableStateFlow<Load<GenreMovies>>(Load.Loading)
    val state: StateFlow<Load<GenreMovies>> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = load { video.genre(id) } }
    }
}

/** A genre's movies, A–Z. */
@Composable
fun GenreScreen(nav: VideoNav, viewModel: GenreViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val genre = (state as? Load.Loaded)?.value
    MusicPage(title = genre?.genre?.name.orEmpty(), onBack = nav.back) { padding ->
        if (genre == null || genre.movies.isEmpty()) {
            LoadingOrEmpty(state is Load.Loading, stringResource(R.string.nothing_here), Modifier.padding(padding))
            return@MusicPage
        }
        TileGrid(
            genre.movies.map { Tile(it.id, it.title, "", it.posterUri, poster = true) },
            onClick = { nav.openVideo(it.id) },
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 96.dp),
        )
    }
}
