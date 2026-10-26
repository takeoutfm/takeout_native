package com.takeoutfm.watch.ui

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.takeoutfm.media.offline.CacheStatus
import com.takeoutfm.shared.listen.parseStreamTitle
import com.takeoutfm.shared.music.ArtistMix
import com.takeoutfm.shared.music.StationKind
import com.takeoutfm.watch.BuildConfig
import com.takeoutfm.watch.R
import kotlinx.coroutines.flow.first

/** The watch app: sign-in by code until there's a session, then the home list. */
@Composable
fun WatchApp(vm: WatchViewModel = hiltViewModel()) {
    val session by vm.session_.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    MaterialTheme {
        if (session.isLoggedIn) Pages(vm) else SignIn(vm)
    }
}

@Composable
private fun SignIn(vm: WatchViewModel) {
    val code by vm.code.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf(vm.endpoint.ifEmpty { "https://takeout.fm" }) }
    ListPage(stringResource(R.string.app_name)) {
        val c = code.code
        if (c == null) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.host_label), style = MaterialTheme.typography.caption2)
                    // Editable with the watch keyboard; most people keep the default.
                    BasicTextField(
                        value = host,
                        onValueChange = { host = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colors.surface, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                    )
                }
            }
            item {
                Chip(
                    onClick = { vm.getCode(host) },
                    label = { Text(stringResource(R.string.get_code)) },
                    enabled = !code.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (code.error) item { Text(stringResource(R.string.cant_reach), color = MaterialTheme.colors.error, textAlign = TextAlign.Center) }
        } else {
            item { Text(stringResource(R.string.code_instructions), textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1) }
            item { Text(c, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp) }
            item { Text(stringResource(R.string.code_or_web, code.link.orEmpty()), textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2) }
            item { Chip(onClick = { vm.getCode(host) }, label = { Text(stringResource(R.string.get_new_code)) }, colors = ChipDefaults.secondaryChipColors()) }
        }
    }
}

@Composable
private fun Pages(vm: WatchViewModel) {
    val nav = rememberSwipeDismissableNavController()
    val play = { load: suspend () -> com.takeoutfm.shared.music.MusicQueue ->
        vm.play(load)
        nav.navigate("player")
    }
    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") { Home(vm, nav::navigate) }
        composable("player") { Player(vm) }
        composable("recent") {
            val recent by vm.recent.collectAsStateWithLifecycle()
            ListPage(stringResource(R.string.recent)) {
                if (recent.isEmpty()) item { Text(stringResource(R.string.nothing_here)) }
                items(recent.size) { i ->
                    val q = recent[i]
                    Entry(q.title, onClick = { vm.playRecent(q.id); nav.navigate("player") }, secondary = q.creator, imageUri = q.coverUri)
                }
            }
        }
        composable("music") {
            val state = load("new") { vm.music.recentlyAdded().first() }
            ListPage(stringResource(R.string.music)) {
                status(state)
                val albums = (state as? Load.Loaded)?.value.orEmpty()
                items(albums.size) { i ->
                    val a = albums[i]
                    Entry(a.name, onClick = { nav.navigate("album/${a.id}") }, secondary = a.artist, imageUri = a.coverUri)
                }
            }
        }
        composable("album/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val state = load(id) { vm.music.albumDetails(id) }
            val downloads by vm.downloads.collectAsStateWithLifecycle()
            val details = (state as? Load.Loaded)?.value
            ListPage(details?.album?.name ?: "") {
                if (details == null) {
                    item { if (state is Load.Failed) Text(stringResource(R.string.cant_load)) else CircularProgressIndicator() }
                    return@ListPage
                }
                item { Text(details.album.artist, color = MaterialTheme.colors.onSurfaceVariant) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play { vm.music.albumQueue(id) } }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play)) }
                        Button(onClick = { play { vm.music.albumQueue(id).let { q -> q.copy(items = q.items.shuffled()) } } }, colors = ButtonDefaults.secondaryButtonColors()) {
                            Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle))
                        }
                        val all = details.tracks.isNotEmpty() && details.tracks.all { downloads[it.etag]?.status == CacheStatus.Cached }
                        Button(onClick = { vm.download { vm.music.albumQueue(id) } }, enabled = !all, colors = ButtonDefaults.secondaryButtonColors()) {
                            Icon(if (all) Icons.Filled.DownloadDone else Icons.Filled.Download, stringResource(if (all) R.string.downloaded else R.string.download))
                        }
                    }
                }
                items(details.tracks.size) { i ->
                    val t = details.tracks[i]
                    Entry(t.title, onClick = { play { vm.music.albumQueue(id, i) } }, secondary = if (downloads[t.etag]?.status == CacheStatus.Cached) "✓ ${t.artist}" else t.artist)
                }
            }
        }
        composable("artists") {
            val state = load("artists") { vm.music.artists().first() }
            ListPage(stringResource(R.string.artists)) {
                status(state)
                val artists = (state as? Load.Loaded)?.value.orEmpty()
                items(artists.size) { i ->
                    val a = artists[i]
                    Entry(a.name, onClick = { nav.navigate("artist/${a.id}") }, secondary = a.genre, imageUri = a.imageUri)
                }
            }
        }
        composable("artist/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val state = load(id) { vm.music.artistDetails(id) }
            val details = (state as? Load.Loaded)?.value
            ListPage(details?.artist?.name ?: "") {
                if (details == null) {
                    item { if (state is Load.Failed) Text(stringResource(R.string.cant_load)) else CircularProgressIndicator() }
                    return@ListPage
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play { vm.music.artistQueue(id, ArtistMix.Shuffle) } }) { Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle)) }
                        Button(onClick = { play { vm.music.artistQueue(id, ArtistMix.Radio) } }, colors = ButtonDefaults.secondaryButtonColors()) { Icon(Icons.Filled.Radio, stringResource(R.string.radio)) }
                    }
                }
                val albums = details.albums + details.singles + details.otherReleases
                items(albums.size) { i ->
                    val a = albums[i]
                    Entry(a.name, onClick = { nav.navigate("album/${a.id}") }, secondary = a.year, imageUri = a.coverUri)
                }
            }
        }
        composable("playlists") {
            val state = load("playlists") { vm.music.playlists() }
            ListPage(stringResource(R.string.playlists)) {
                item { Entry(stringResource(R.string.favorites), onClick = { play { vm.music.favoritesQueue() } }, icon = Icons.Filled.Favorite) }
                status(state)
                val lists = (state as? Load.Loaded)?.value.orEmpty()
                items(lists.size) { i ->
                    val p = lists[i]
                    Entry(p.name, onClick = { play { vm.music.playlistQueue(p.id) } }, icon = Icons.AutoMirrored.Filled.QueueMusic)
                }
            }
        }
        composable("radio") {
            val state = load("radio") { vm.music.radio().sortedBy { it.kind != StationKind.Stream }.flatMap { it.stations } }
            ListPage(stringResource(R.string.radio)) {
                status(state)
                val stations = (state as? Load.Loaded)?.value.orEmpty()
                items(stations.size) { i ->
                    val s = stations[i]
                    Entry(s.name, onClick = { play { vm.music.stationQueue(s.id) } }, imageUri = s.imageUri)
                }
            }
        }
        composable("podcasts") {
            val state = load("podcasts") { vm.podcasts.series(subscribedOnly = true).ifEmpty { vm.podcasts.series(subscribedOnly = false) } }
            ListPage(stringResource(R.string.podcasts)) {
                status(state)
                val series = (state as? Load.Loaded)?.value.orEmpty()
                items(series.size) { i ->
                    val s = series[i]
                    Entry(s.title, onClick = { nav.navigate("podcast/${s.id}") }, secondary = s.author, imageUri = s.imageUri)
                }
            }
        }
        composable("podcast/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val state = load(id) { vm.podcasts.details(id) }
            val details = (state as? Load.Loaded)?.value
            ListPage(details?.series?.title ?: "") {
                if (details == null) {
                    item { if (state is Load.Failed) Text(stringResource(R.string.cant_load)) else CircularProgressIndicator() }
                    return@ListPage
                }
                items(details.episodes.size) { i ->
                    val e = details.episodes[i]
                    val s = details.series
                    Entry(e.title, onClick = { play { vm.podcasts.episodesQueue(s.title, s.imageUri, details.episodes, i) } }, secondary = e.date.take(10))
                }
            }
        }
        composable("downloads") {
            val downloads by vm.downloads.collectAsStateWithLifecycle()
            val list = downloads.values.sortedWith(compareBy({ it.info.groupTitle }, { it.item.title }))
            ListPage(stringResource(R.string.downloads)) {
                if (list.isEmpty()) item { Text(stringResource(R.string.nothing_here)) }
                items(list.size) { i ->
                    val d = list[i]
                    val status = d.status
                    Entry(
                        d.item.title,
                        onClick = { vm.playDownloads(list.map { it.item }, i); nav.navigate("player") },
                        secondary = if (status is CacheStatus.Partial) "${stringResource(R.string.downloading)} ${status.percent}%" else d.info.groupTitle,
                        imageUri = d.item.imageUri,
                    )
                }
                if (list.isNotEmpty()) {
                    item { Entry(stringResource(R.string.delete), onClick = { vm.removeDownloads(downloads.keys) }, icon = Icons.Filled.DeleteSweep) }
                }
            }
        }
        composable("settings") { Settings(vm) }
    }
}

@Composable
private fun Home(vm: WatchViewModel, go: (String) -> Unit) {
    val playing by vm.nowPlaying.collectAsStateWithLifecycle()
    ListPage(stringResource(R.string.app_name)) {
        playing.current?.let { item ->
            item {
                Chip(
                    onClick = { go("player") },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(stringResource(R.string.now_playing), maxLines = 1) },
                    icon = { Icon(if (playing.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null) },
                )
            }
        }
        item { Entry(stringResource(R.string.recent), onClick = { go("recent") }, icon = Icons.Filled.History) }
        item { Entry(stringResource(R.string.music), onClick = { go("music") }, icon = Icons.Filled.Speaker) }
        item { Entry(stringResource(R.string.artists), onClick = { go("artists") }, icon = Icons.Filled.People) }
        item { Entry(stringResource(R.string.playlists), onClick = { go("playlists") }, icon = Icons.AutoMirrored.Filled.QueueMusic) }
        item { Entry(stringResource(R.string.radio), onClick = { go("radio") }, icon = Icons.Filled.Radio) }
        item { Entry(stringResource(R.string.podcasts), onClick = { go("podcasts") }, icon = Icons.Filled.Podcasts) }
        item { Entry(stringResource(R.string.downloads), onClick = { go("downloads") }, icon = Icons.Filled.DownloadDone) }
        item { Entry(stringResource(R.string.settings), onClick = { go("settings") }, icon = Icons.Filled.Settings) }
    }
}

/**
 * Now Playing: progress ring, title and artist (a stream's song from its ICY title), previous
 * · play/pause · next (play/pause only for live streams); the crown turns the volume.
 */
@Composable
private fun Player(vm: WatchViewModel) {
    val playing by vm.nowPlaying.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(timeText = { TimeText() }) {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (it.verticalScrollPixels > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI,
                    )
                    true
                }
                .focusRequester(focus)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            if (!playing.isLive && playing.durationMs > 0) {
                CircularProgressIndicator(
                    progress = (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    startAngle = 300f,
                    endAngle = 240f,
                    strokeWidth = 4.dp,
                )
            }
            val item = playing.current
            if (item == null) {
                Text(stringResource(R.string.nothing_here))
                return@Box
            }
            val onAir = playing.streamTitle?.let(::parseStreamTitle)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                if (playing.isLive) {
                    Text(
                        stringResource(R.string.live),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.background(Color(0xFFD32F2F), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(
                    onAir?.second ?: playing.streamTitle ?: item.title,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    onAir?.first ?: item.artist,
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    if (!playing.isLive) {
                        Button(onClick = vm::previous, colors = ButtonDefaults.iconButtonColors(), modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous))
                        }
                    }
                    Button(onClick = vm::togglePlay, modifier = Modifier.size(ButtonDefaults.LargeButtonSize)) {
                        Icon(
                            if (playing.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (playing.playWhenReady) R.string.pause else R.string.play),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    if (!playing.isLive) {
                        Button(onClick = vm::next, colors = ButtonDefaults.iconButtonColors(), modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                            Icon(Icons.Filled.SkipNext, stringResource(R.string.next))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings(vm: WatchViewModel) {
    val reportPlays by vm.settings.reportPlays.collectAsStateWithLifecycle()
    val caching by vm.settings.playbackCaching.collectAsStateWithLifecycle()
    ListPage(stringResource(R.string.settings)) {
        item {
            ToggleChip(
                checked = caching,
                onCheckedChange = vm.settings::setPlaybackCaching,
                label = { Text(stringResource(R.string.playback_caching)) },
                toggleControl = { Switch(checked = caching) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ToggleChip(
                checked = reportPlays,
                onCheckedChange = vm.settings::setReportPlays,
                label = { Text(stringResource(R.string.track_activity)) },
                toggleControl = { Switch(checked = reportPlays) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Entry(stringResource(R.string.server), onClick = {}, secondary = vm.endpoint) }
        item { Entry(stringResource(R.string.sign_out), onClick = vm::signOut, icon = Icons.Filled.Logout) }
        item { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.caption3) }
    }
}
