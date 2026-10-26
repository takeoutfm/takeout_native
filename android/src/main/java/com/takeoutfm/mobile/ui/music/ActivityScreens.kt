package com.takeoutfm.mobile.ui.music

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.common.TileItem
import com.takeoutfm.mobile.ui.common.Tile
import com.takeoutfm.mobile.ui.main.MainViewModel
import com.takeoutfm.shared.music.ActivityInterval
import com.takeoutfm.shared.music.ActivityStats
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.PlayedQueue
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.music.StreamSong
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.music.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class RecentViewModel @Inject constructor(
    private val history: MusicHistory,
    private val streams: StreamHistory,
) : ViewModel() {
    val played: StateFlow<List<PlayedQueue>> = history.entries
    val songs: StateFlow<List<StreamSong>> = streams.songs

    fun clear() {
        history.clear()
        streams.clear()
    }
}

/** The Recent tab: queues played on this device, then songs heard on streams. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(main: MainViewModel, viewModel: RecentViewModel = hiltViewModel()) {
    val played by viewModel.played.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(stringResource(R.string.nav_history)) },
                actions = {
                    if (played.isNotEmpty() || songs.isNotEmpty()) {
                        IconButton(onClick = { confirm = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear_history))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (played.isEmpty() && songs.isEmpty()) {
            LoadingOrEmpty(false, stringResource(R.string.no_recent), Modifier.padding(padding))
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (played.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.recently_played), style = MaterialTheme.typography.titleMedium) }
                items(played, key = { it.id }) { q ->
                    TileItem(Tile(q.id, q.title, q.creator, q.coverUri)) { main.playRecent(q.id) }
                }
            }
            if (songs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.stream_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(songs, key = { "${it.heardAt}-${it.title}" }, span = { GridItemSpan(maxLineSpan) }) { song ->
                    StreamSongRow(song)
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(R.string.clear_history_question)) },
            confirmButton = { TextButton(onClick = { viewModel.clear(); confirm = false }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StreamSongRow(song: StreamSong) {
    val parsed = com.takeoutfm.shared.listen.parseStreamTitle(song.title)
    ListItem(
        leadingContent = { Art(song.imageUri, song.station, Modifier.size(44.dp), RoundedCornerShape(6.dp)) },
        headlineContent = { Text(parsed?.second ?: song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(parsed?.first, song.station, ago(song.heardAt)).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** "3 minutes ago", "2 days ago". */
fun ago(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@HiltViewModel
class RecentTracksViewModel @Inject constructor(music: TakeoutMusicRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Track>>>(Load.Loading)
    val state: StateFlow<Load<List<Track>>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                Load.Loaded(music.recentTracks())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
        }
    }
}

/** Recent Tracks: the user's Takeout listening history (every device). */
@Composable
fun RecentTracksScreen(main: MainViewModel, nav: MusicNav, viewModel: RecentTracksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by main.favoriteEtags.collectAsStateWithLifecycle()
    val title = stringResource(R.string.recent_tracks)
    val tracks = (state as? Load.Loaded)?.value.orEmpty()
    MusicPage(
        title = title,
        onBack = nav.back,
        actions = { PlayShuffle(enabled = tracks.isNotEmpty(), onPlay = { main.playTracks(title, tracks) }, onShuffle = { main.playTracks(title, tracks, shuffle = true) }) },
    ) { padding ->
        if (tracks.isEmpty()) {
            LoadingOrEmpty(state is Load.Loading, stringResource(if (state is Load.Failed) R.string.cant_load else R.string.nothing_here), Modifier.padding(padding))
            return@MusicPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            itemsIndexed(tracks) { index, track ->
                TrackRow(
                    track = track,
                    favorite = track.etag in favorites,
                    onClick = { main.playTracks(title, tracks, index) },
                    onFavorite = { main.setTrackFavorite(track.etag, it) },
                    onEnqueue = { last -> main.enqueueTrack(track.id, last) },
                    showAlbum = true,
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun PlayShuffle(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    IconButton(onClick = onPlay, enabled = enabled) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play_all)) }
    IconButton(onClick = onShuffle, enabled = enabled) { Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle)) }
}

enum class ActivityType(val label: Int) { Artists(R.string.search_artists), Releases(R.string.search_releases), Tracks(R.string.search_tracks) }

@HiltViewModel
class ActivityViewModel @Inject constructor(private val music: TakeoutMusicRepository) : ViewModel() {
    private val _interval = MutableStateFlow(ActivityInterval.Recent)
    val interval: StateFlow<ActivityInterval> = _interval.asStateFlow()

    private val _state = MutableStateFlow<Load<ActivityStats>>(Load.Loading)
    val state: StateFlow<Load<ActivityStats>> = _state.asStateFlow()

    init {
        load()
    }

    fun select(interval: ActivityInterval) {
        _interval.value = interval
        load()
    }

    private fun load() {
        val interval = _interval.value
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = try {
                Load.Loaded(music.activity(interval))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
        }
    }
}

/** Activity: most played artists, releases or tracks over a period, with counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(main: MainViewModel, nav: MusicNav, viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val interval by viewModel.interval.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf(ActivityType.Artists) }
    val title = stringResource(R.string.activity)
    val stats = (state as? Load.Loaded)?.value
    val tracks = stats?.tracks.orEmpty().map { it.item }
    MusicPage(
        title = title,
        onBack = nav.back,
        actions = { PlayShuffle(enabled = tracks.isNotEmpty(), onPlay = { main.playTracks(title, tracks) }, onShuffle = { main.playTracks(title, tracks, shuffle = true) }) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ActivityType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = t == type,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, ActivityType.entries.size),
                    ) { Text(stringResource(t.label)) }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActivityInterval.entries.forEach { i ->
                    FilterChip(selected = i == interval, onClick = { viewModel.select(i) }, label = { Text(stringResource(i.label())) })
                }
            }
            if (stats == null) {
                LoadingOrEmpty(state is Load.Loading, stringResource(R.string.cant_load))
                return@Column
            }
            val count = @Composable { n: Int -> Text(stringResource(R.string.play_count, n), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                when (type) {
                    ActivityType.Artists -> itemsIndexed(stats.artists) { _, (artist, n) ->
                        ListItem(
                            leadingContent = { Art(artist.imageUri, artist.name.take(1), Modifier.size(48.dp), RoundedCornerShape(50)) },
                            headlineContent = { Text(artist.name) },
                            trailingContent = { count(n) },
                            modifier = Modifier.clickable { nav.openArtist(artist.id) },
                        )
                    }
                    ActivityType.Releases -> itemsIndexed(stats.albums) { _, (album, n) ->
                        ListItem(
                            leadingContent = { Art(album.coverUri, album.name, Modifier.size(48.dp), RoundedCornerShape(6.dp)) },
                            headlineContent = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { count(n) },
                            modifier = Modifier.clickable { nav.openAlbum(album.id) },
                        )
                    }
                    ActivityType.Tracks -> itemsIndexed(stats.tracks) { index, (track, n) ->
                        ListItem(
                            leadingContent = { Art(track.coverUri, track.album, Modifier.size(48.dp), RoundedCornerShape(6.dp)) },
                            headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { count(n) },
                            modifier = Modifier.clickable { main.playTracks(title, tracks, index) },
                        )
                    }
                }
            }
        }
    }
}

private fun ActivityInterval.label(): Int = when (this) {
    ActivityInterval.Recent -> R.string.activity_recent
    ActivityInterval.LastWeek -> R.string.activity_last_week
    ActivityInterval.LastMonth -> R.string.activity_last_month
    ActivityInterval.ThisMonth -> R.string.activity_this_month
}
