package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.takeoutfm.media.NowPlaying
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.main.MainViewModel
import com.takeoutfm.shared.listen.parseStreamTitle

/**
 * Now Playing: the artist's background behind a large cover, title / artist / album, the seek
 * bar, previous · −10 s · play/pause · +30 s · next, repeat, ♥ and the queue (a bottom sheet).
 * A live stream shows LIVE, what's on air (its ICY title) and play/pause only.
 */
@Composable
fun PlayerScreen(main: MainViewModel) {
    val playing by main.nowPlaying.collectAsStateWithLifecycle()
    val favorites by main.favoriteEtags.collectAsStateWithLifecycle()
    val item = playing.current
    if (item == null) {
        TabPage(title = stringResource(R.string.nav_player)) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.nothing_playing), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.nothing_playing_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        return
    }
    var showQueue by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (item.backgroundUri.isNotEmpty()) {
            AsyncImage(item.backgroundUri, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.25f, modifier = Modifier.fillMaxSize())
        }
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
            val wide = maxWidth > maxHeight
            val cover = @Composable { m: Modifier -> Art(item.imageUri, item.album, m.aspectRatio(1f), RoundedCornerShape(12.dp)) }
            val details = @Composable {
                PlayerDetails(
                    playing = playing,
                    favorite = item.etag in favorites,
                    main = main,
                    onQueue = { showQueue = true },
                )
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    cover(Modifier.fillMaxHeight())
                    Column(Modifier.weight(1f)) { details() }
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    cover(Modifier.widthIn(max = 420.dp).fillMaxWidth())
                    Column(Modifier.padding(top = 24.dp).widthIn(max = 480.dp)) { details() }
                }
            }
        }
    }
    if (showQueue) QueueSheet(playing, onPlay = { main.playAt(it); showQueue = false }, onDismiss = { showQueue = false })
}

@Composable
private fun PlayerDetails(playing: NowPlaying, favorite: Boolean, main: MainViewModel, onQueue: () -> Unit) {
    val item = playing.current ?: return
    // A stream's song, from its ICY title ("Artist - Title"), above the station.
    val onAir = playing.streamTitle?.let(::parseStreamTitle)
    val title = onAir?.second ?: playing.streamTitle ?: item.title
    val subtitle = onAir?.first ?: item.artist
    val third = if (playing.isLive) item.title.takeIf { it != title }.orEmpty() else item.album
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (third.isNotEmpty()) Text(third, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    playing.error?.let { error ->
        Text(stringResource(R.string.cant_play_reason, error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }

    if (playing.isLive) {
        LiveBadge(playing.isPlaying, Modifier.padding(vertical = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            PlayPause(playing, main)
        }
        return
    }
    SeekBar(playing, main)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = main::previous) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), Modifier.size(32.dp)) }
        IconButton(onClick = { main.seekBy(-10_000) }) { Icon(painterResource(R.drawable.replay_10_24px), stringResource(R.string.back_10)) }
        PlayPause(playing, main)
        IconButton(onClick = { main.seekBy(30_000) }) { Icon(painterResource(R.drawable.forward_30_24px), stringResource(R.string.forward_30)) }
        IconButton(onClick = main::next) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next), Modifier.size(32.dp)) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = main::cycleRepeat) {
            Icon(
                painterResource(
                    when (playing.repeatMode) {
                        Player.REPEAT_MODE_ALL -> R.drawable.repeat_on_24dp
                        Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on_24dp
                        else -> R.drawable.repeat_24dp
                    }
                ),
                contentDescription = stringResource(R.string.repeat),
                tint = if (playing.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
        if (item.etag.isNotEmpty()) {
            IconButton(onClick = { main.setTrackFavorite(item.etag, !favorite) }) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(if (favorite) R.string.unfavorite else R.string.favorite),
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue)) }
    }
}

@Composable
private fun PlayPause(playing: NowPlaying, main: MainViewModel) {
    FilledIconButton(onClick = main::togglePlay, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
        Icon(
            if (playing.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (playing.playWhenReady) R.string.pause else R.string.play),
            modifier = Modifier.size(40.dp),
        )
    }
}

/** Position slider; dragging previews the time, letting go seeks. */
@Composable
private fun SeekBar(playing: NowPlaying, main: MainViewModel) {
    val duration = playing.durationMs.coerceAtLeast(1)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragValue else (playing.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.padding(top = 16.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { main.seekTo((dragValue * duration).toLong()); dragging = false },
            enabled = playing.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time((fraction * duration).toLong()), style = MaterialTheme.typography.labelSmall)
            Text(if (playing.durationMs > 0) time(playing.durationMs) else "", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The red LIVE pill; its dot is green while the stream plays (as on the TV). */
@Composable
fun LiveBadge(playing: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0xFFD32F2F), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (playing) Color(0xFF69F0AE) else Color(0xFFBDBDBD), CircleShape))
        Text(stringResource(R.string.live), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(playing: NowPlaying, onPlay: (Int) -> Unit, onDismiss: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (playing.index - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.queue), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        LazyColumn(state = listState) {
            itemsIndexed(playing.items) { index, item ->
                val current = index == playing.index
                ListItem(
                    leadingContent = { Art(item.imageUri, item.album, Modifier.size(44.dp), RoundedCornerShape(6.dp)) },
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else null) },
                    supportingContent = { Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = if (current) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
                    modifier = Modifier.clickable { onPlay(index) },
                )
            }
        }
    }
}

/** The persistent mini player (wide layouts): cover, title, play/pause, next; tap opens Now Playing. */
@Composable
fun MiniPlayer(playing: NowPlaying, main: MainViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val item = playing.current ?: return
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column {
            if (!playing.isLive && playing.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.clickable(onClick = onOpen).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(item.imageUri, item.album, Modifier.size(48.dp), RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    val onAir = playing.streamTitle?.let(::parseStreamTitle)
                    Text(onAir?.second ?: playing.streamTitle ?: item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(onAir?.first ?: item.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (playing.isLive) LiveBadge(playing.isPlaying, Modifier.padding(end = 8.dp))
                IconButton(onClick = main::togglePlay) {
                    Icon(if (playing.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (playing.playWhenReady) R.string.pause else R.string.play))
                }
                if (!playing.isLive) {
                    IconButton(onClick = main::next) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next)) }
                }
            }
        }
    }
}
