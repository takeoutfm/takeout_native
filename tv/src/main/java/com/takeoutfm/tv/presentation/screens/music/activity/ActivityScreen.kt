package com.takeoutfm.tv.presentation.screens.music.activity

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.takeoutfm.shared.music.ActivityInterval
import com.takeoutfm.shared.music.ActivityStats
import com.takeoutfm.shared.music.Album
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.music.Track
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.screens.favourites.MovieFilterChip
import com.takeoutfm.tv.presentation.screens.music.common.MusicListRow
import com.takeoutfm.tv.presentation.screens.music.common.MusicMessage
import com.takeoutfm.tv.presentation.screens.music.common.MusicTabColumn
import com.takeoutfm.tv.presentation.screens.music.common.MusicTrackRow
import com.takeoutfm.tv.presentation.screens.music.common.TrackMenuDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Activity tab lists. */
enum class ActivityKind(val label: Int) {
    Artists(R.string.music_favorite_artists),
    Releases(R.string.music_releases),
    Tracks(R.string.music_favorite_tracks),
}

/** The periods, in the phone's order. */
private val Intervals = listOf(
    ActivityInterval.Recent to R.string.activity_recent,
    ActivityInterval.LastWeek to R.string.activity_last_week,
    ActivityInterval.ThisMonth to R.string.activity_this_month,
    ActivityInterval.LastMonth to R.string.activity_last_month,
)

/**
 * Activity (LIB-14): the server's play stats, as on the phone and Mac: the most played artists,
 * releases or tracks over a period, each with its play count. An artist or release opens its
 * page; a track plays from there with the rest of the list (a long press for its menu).
 */
@Composable
fun ActivityScreen(
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    openNowPlaying: () -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val childPadding = rememberChildPadding()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val interval by viewModel.interval.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    menuTrack?.let { TrackMenuDialog(it, onDismissRequest = { menuTrack = null }) }

    MusicTabColumn(onScroll = onScroll, isTopBarVisible = isTopBarVisible) {
        item(key = "kinds") {
            Row(Modifier.padding(start = childPadding.start, top = childPadding.top, end = childPadding.end)) {
                ActivityKind.entries.forEach { k ->
                    MovieFilterChip(
                        label = stringResource(k.label),
                        isChecked = k == kind,
                        onCheckedChange = { viewModel.show(k) },
                    )
                }
            }
        }
        item(key = "intervals") {
            Row(Modifier.padding(start = childPadding.start, top = 12.dp, end = childPadding.end, bottom = 16.dp)) {
                Intervals.forEach { (i, label) ->
                    MovieFilterChip(
                        label = stringResource(label),
                        isChecked = i == interval,
                        onCheckedChange = { viewModel.period(i) },
                    )
                }
            }
        }
        val rowModifier = Modifier.padding(horizontal = childPadding.start)
        when (val s = state) {
            ActivityState.Loading -> item(key = "loading") {
                MusicMessage(stringResource(R.string.searching))
            }
            ActivityState.Error -> item(key = "error") {
                Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
            }
            is ActivityState.Ready -> {
                val stats = s.stats
                val empty = when (kind) {
                    ActivityKind.Artists -> stats.artists.isEmpty()
                    ActivityKind.Releases -> stats.albums.isEmpty()
                    ActivityKind.Tracks -> stats.tracks.isEmpty()
                }
                if (empty) item(key = "empty") { MusicMessage(stringResource(R.string.activity_nothing)) }
                when (kind) {
                    ActivityKind.Artists -> itemsIndexed(stats.artists, key = { i, c -> "a-$i-${c.item.id}" }) { _, counted ->
                        MusicListRow(
                            imageUri = counted.item.imageUri,
                            placeholderKey = counted.item.id,
                            title = counted.item.name,
                            subtitle = counted.item.genre,
                            trailing = plays(counted.count),
                            onClick = { onArtistClick(counted.item) },
                            modifier = rowModifier,
                        )
                    }
                    ActivityKind.Releases -> itemsIndexed(stats.albums, key = { i, c -> "r-$i-${c.item.id}" }) { _, counted ->
                        MusicListRow(
                            imageUri = counted.item.coverUri,
                            placeholderKey = counted.item.id,
                            title = counted.item.name,
                            subtitle = counted.item.artist,
                            trailing = plays(counted.count),
                            onClick = { onAlbumClick(counted.item) },
                            modifier = rowModifier,
                        )
                    }
                    ActivityKind.Tracks -> itemsIndexed(stats.tracks, key = { i, c -> "t-$i-${c.item.id}" }) { index, counted ->
                        MusicTrackRow(
                            track = counted.item,
                            trailing = plays(counted.count),
                            onClick = {
                                viewModel.playTracks(stats.tracks.map { it.item }, index)
                                openNowPlaying()
                            },
                            onLongClick = { menuTrack = counted.item },
                            modifier = rowModifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun plays(count: Int) = pluralStringResource(R.plurals.activity_plays, count, count)

sealed interface ActivityState {
    data object Loading : ActivityState
    data object Error : ActivityState
    data class Ready(val stats: ActivityStats) : ActivityState
}

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: TakeoutMusicRepository,
    private val player: MusicPlayer,
) : ViewModel() {
    private val _kind = MutableStateFlow(ActivityKind.Artists)
    val kind: StateFlow<ActivityKind> = _kind.asStateFlow()

    private val _interval = MutableStateFlow(ActivityInterval.Recent)
    val interval: StateFlow<ActivityInterval> = _interval.asStateFlow()

    private val _state = MutableStateFlow<ActivityState>(ActivityState.Loading)
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    private var loading: Job? = null

    init {
        load()
    }

    fun show(kind: ActivityKind) {
        _kind.value = kind
    }

    fun period(interval: ActivityInterval) {
        if (interval == _interval.value) return
        _interval.value = interval
        load()
    }

    fun load() {
        loading?.cancel()
        val asked = _interval.value
        if (_state.value !is ActivityState.Ready) _state.value = ActivityState.Loading
        loading = viewModelScope.launch {
            _state.value = try {
                ActivityState.Ready(named(repository.activity(asked)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActivityState.Error
            }
        }
    }

    /** Plays the listed [tracks] from the [index]th. */
    fun playTracks(tracks: List<Track>, index: Int) {
        player.play(repository.tracksQueue(ActivityTitle, tracks, index))
    }

    companion object {
        private const val ActivityTitle = "Activity"

        /**
         * [stats] without the entries the server sends with no name (a release no longer in the
         * library came back as a blank row that opened nothing).
         */
        fun named(stats: ActivityStats) = ActivityStats(
            artists = stats.artists.filter { it.item.name.isNotEmpty() },
            albums = stats.albums.filter { it.item.name.isNotEmpty() },
            tracks = stats.tracks.filter { it.item.title.isNotEmpty() },
        )
    }
}
