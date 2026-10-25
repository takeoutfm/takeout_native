package com.google.jetstream.presentation.screens.music.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.jetstream.R
import com.google.jetstream.data.music.MusicHistory
import com.google.jetstream.data.music.TakeoutMusicRepository
import com.google.jetstream.playback.MusicPlayer
import com.google.jetstream.playback.NowPlaying
import com.google.jetstream.presentation.common.Error
import com.google.jetstream.presentation.common.PlayerLoading
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.screens.music.common.MusicImage
import com.google.jetstream.presentation.theme.JetStreamButtonShape
import com.google.jetstream.presentation.theme.JetStreamCardShape
import com.google.jetstream.presentation.utils.focusOnInitialVisibility
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object MusicPlayerScreen {
    const val QueueBundleKey = "queue"
}

/**
 * Starts playing a queue (album, artist mix…) in the background music service, then shows Now
 * Playing. Back leaves the music playing; the mini player brings this view back.
 */
@Composable
fun MusicPlayerScreen(
    onBackPressed: () -> Unit,
    viewModel: MusicPlayerViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackPressed)
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    when (loading) {
        QueueLoad.Loading -> PlayerLoading(Modifier.fillMaxSize())
        QueueLoad.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        QueueLoad.Started -> {
            val state by viewModel.nowPlaying.collectAsStateWithLifecycle()
            // Until the service reports the queue (first connection), keep the spinner.
            if (state.hasQueue) {
                val favorites by viewModel.favoriteTrackEtags.collectAsStateWithLifecycle()
                NowPlayingContent(
                    state = state,
                    player = viewModel.player,
                    favoriteEtags = favorites,
                    onToggleFavorite = viewModel::toggleFavorite,
                )
            } else {
                PlayerLoading(Modifier.fillMaxSize())
            }
        }
    }
}

/** Now Playing for whatever the music service is playing (opened from the mini player). */
@Composable
fun NowPlayingScreen(
    onBackPressed: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackPressed)
    val state by viewModel.nowPlaying.collectAsStateWithLifecycle()
    if (!state.hasQueue) {
        // Nothing queued (e.g. it was stopped meanwhile): nothing to show here.
        LaunchedEffect(Unit) { onBackPressed() }
        return
    }
    val favorites by viewModel.favoriteTrackEtags.collectAsStateWithLifecycle()
    NowPlayingContent(
        state = state,
        player = viewModel.player,
        favoriteEtags = favorites,
        onToggleFavorite = viewModel::toggleFavorite,
    )
}

@Composable
private fun NowPlayingContent(
    state: NowPlaying,
    player: MusicPlayer,
    favoriteEtags: Set<String>,
    onToggleFavorite: (etag: String, favorite: Boolean) -> Unit,
) {
    val item = state.current ?: return
    val view = LocalView.current
    // Keep the screensaver away only while music is actually playing here.
    DisposableEffect(state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    val childPadding = rememberChildPadding()
    var showQueue by remember { mutableStateOf(false) }
    val queueButton = remember { FocusRequester() }
    // Declared after the screen's own BackHandler, so it wins while the panel is open.
    BackHandler(enabled = showQueue) { showQueue = false }

    Box(Modifier.fillMaxSize()) {
        NowPlayingBackground(
            backgroundUri = item.backgroundUri,
            coverUri = item.imageUri,
            placeholderKey = item.album,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = childPadding.start),
        ) {
            MusicImage(
                url = item.imageUri,
                contentDescription = item.album,
                modifier = Modifier
                    .size(260.dp)
                    .clip(JetStreamCardShape),
                placeholderKey = item.album,
            )
            Column(Modifier.padding(start = 48.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = item.album,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (state.isLive) {
                    LiveBadge(
                        isPlaying = state.isPlaying,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                } else {
                    SeekBar(
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        onSeek = player::seekBy,
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .width(520.dp),
                    )
                }
                Controls(
                    state = state,
                    player = player,
                    onOpenQueue = { showQueue = true },
                    // Radio streams have no ETag, so they can't be favorites.
                    isFavorite = item.etag.isNotEmpty() && item.etag in favoriteEtags,
                    onToggleFavorite = item.etag.takeIf { it.isNotEmpty() }?.let { etag ->
                        { onToggleFavorite(etag, etag !in favoriteEtags) }
                    },
                    queueButton = queueButton,
                )
                val status = when {
                    state.error != null ->
                        stringResource(R.string.music_cant_play, state.error)
                    state.isLive -> state.streamTitle?.let {
                        stringResource(R.string.music_now_on_air, it)
                    }
                    else -> state.items.getOrNull(state.index + 1)?.let { next ->
                        stringResource(R.string.music_up_next, next.title, next.artist)
                    }
                }
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = showQueue,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            QueuePanel(
                state = state,
                onPlay = { index ->
                    player.playAt(index)
                    showQueue = false
                },
                onClose = { showQueue = false },
                isOpen = showQueue,
            )
        }
        // Closing the panel returns focus to the Queue button (not on first showing).
        var queueWasOpen by remember { mutableStateOf(false) }
        LaunchedEffect(showQueue) {
            if (showQueue) {
                queueWasOpen = true
            } else if (queueWasOpen) {
                runCatching { queueButton.requestFocus() }
            }
        }
    }
}

/**
 * The playing track's artist background with the left side darkened for the text (as on the
 * artist page); without one, the cover, dimmed.
 */
@Composable
private fun NowPlayingBackground(backgroundUri: String, coverUri: String, placeholderKey: Any) {
    val background = MaterialTheme.colorScheme.background
    if (backgroundUri.isNotEmpty()) {
        Crossfade(targetState = backgroundUri, label = "background") { uri ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(uri).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to background.copy(alpha = 0.92f),
                        0.55f to background.copy(alpha = 0.7f),
                        1f to background.copy(alpha = 0.25f),
                    )
                )
        )
    } else {
        MusicImage(
            url = coverUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.25f),
            placeholderKey = placeholderKey,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(background.copy(alpha = 0.6f))
        )
    }
}

/**
 * The whole queue as a text list on the right: number, title, artist (and a small cover when the
 * queue mixes albums, e.g. Radio). Opens on the playing track; selecting one plays it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QueuePanel(
    state: NowPlaying,
    onPlay: (index: Int) -> Unit,
    onClose: () -> Unit,
    /** False while it slides out: focus is then let go, back to the Queue button. */
    isOpen: Boolean,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (state.index - 2).coerceAtLeast(0)
    )
    val showCovers = remember(state.items) {
        state.items.map { it.album }.distinct().size > 1
    }
    val currentFocused = remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxHeight()
            .width(520.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .padding(vertical = 32.dp)
            // Left closes the panel, like Back (it slid in from the right); focus can't wander
            // out to the controls behind it.
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_LEFT) {
                    return@onPreviewKeyEvent false
                }
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onClose()
                true
            }
            .focusProperties {
                exit = { if (isOpen) FocusRequester.Cancel else FocusRequester.Default }
            }
            .focusGroup()
    ) {
        Text(
            text = stringResource(R.string.music_queue),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 32.dp, bottom = 16.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.focusGroup(),
        ) {
            itemsIndexed(state.items, key = { index, _ -> "q-$index" }) { index, track ->
                val isCurrent = index == state.index
                Surface(
                    onClick = { onPlay(index) },
                    shape = ClickableSurfaceDefaults.shape(JetStreamButtonShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isCurrent) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        },
                        focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .then(
                            // Focus the playing track once it's laid out.
                            if (isCurrent) Modifier.focusOnInitialVisibility(currentFocused)
                            else Modifier
                        ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (isCurrent) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = stringResource(R.string.music_now_playing),
                                modifier = Modifier
                                    .width(40.dp)
                                    .size(20.dp),
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.width(40.dp),
                            )
                        }
                        if (showCovers) {
                            MusicImage(
                                url = track.imageUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(40.dp)
                                    .clip(JetStreamCardShape),
                                placeholderKey = track.album,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    state: NowPlaying,
    player: MusicPlayer,
    onOpenQueue: () -> Unit,
    queueButton: FocusRequester,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        // A live stream only plays or pauses: nothing to skip, repeat, favorite or queue.
        val live = state.isLive
        if (!live) {
            ControlButton(
                icon = Icons.Default.SkipPrevious,
                description = stringResource(R.string.music_previous),
                onClick = player::previous,
            )
        }
        val firstFocus = remember { mutableStateOf(false) }
        ControlButton(
            icon = if (state.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
            description = stringResource(
                if (state.playWhenReady) R.string.music_pause else R.string.music_play
            ),
            large = true,
            onClick = player::playPause,
            modifier = Modifier.focusOnInitialVisibility(firstFocus),
        )
        if (live) return@Row
        ControlButton(
            icon = Icons.Default.SkipNext,
            description = stringResource(R.string.music_next),
            onClick = player::next,
        )
        ControlButton(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                Icons.Default.RepeatOne
            } else {
                Icons.Default.Repeat
            },
            description = stringResource(R.string.music_repeat),
            active = state.repeatMode != Player.REPEAT_MODE_OFF,
            onClick = player::cycleRepeat,
        )
        if (onToggleFavorite != null) {
            ControlButton(
                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                description = stringResource(
                    if (isFavorite) R.string.music_favorite else R.string.music_add_favorite
                ),
                active = isFavorite,
                onClick = onToggleFavorite,
            )
        }
        if (state.items.size > 1) {
            ControlButton(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                description = stringResource(R.string.music_queue),
                onClick = onOpenQueue,
                modifier = Modifier.focusRequester(queueButton),
            )
        }
    }
}

/**
 * The live-stream indicator in place of the seek bar (and, [small], in the mini player).
 * Playing: a solid red "LIVE" pill with bouncing equalizer bars. Paused: a grey outlined
 * "PAUSED" pill with a still dot, so the state reads from across the room.
 */
@Composable
fun LiveBadge(isPlaying: Boolean, modifier: Modifier = Modifier, small: Boolean = false) {
    val shape = RoundedCornerShape(if (small) 4.dp else 6.dp)
    // The surrounding content colour, so it also reads on a focused (light) mini player.
    val grey = LocalContentColor.current.copy(alpha = 0.6f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .then(
                if (isPlaying) {
                    Modifier.background(LiveRed, shape)
                } else {
                    Modifier.border(if (small) 1.dp else 2.dp, grey, shape)
                }
            )
            .padding(
                horizontal = if (small) 6.dp else 12.dp,
                vertical = if (small) 2.dp else 6.dp,
            ),
    ) {
        if (isPlaying) {
            EqualizerBars(height = if (small) 8.dp else 14.dp)
        } else {
            Box(
                Modifier
                    .size(if (small) 6.dp else 10.dp)
                    .background(grey, CircleShape)
            )
        }
        Text(
            text = stringResource(if (isPlaying) R.string.music_live else R.string.music_paused),
            style = if (small) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.titleSmall,
            color = if (isPlaying) Color.White else grey,
            modifier = Modifier.padding(start = if (small) 4.dp else 8.dp),
        )
    }
}

/** Three white bars bouncing out of step, like a level meter. */
@Composable
private fun EqualizerBars(height: Dp) {
    val transition = rememberInfiniteTransition(label = "eq")
    val barWidth = height / 4
    Row(
        horizontalArrangement = Arrangement.spacedBy(barWidth / 2),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(height),
    ) {
        listOf(520, 380, 640).forEachIndexed { i, period ->
            val fraction by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(period, delayMillis = i * 90),
                    RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(barWidth)
                    .fillMaxHeight(fraction)
                    .background(Color.White, RoundedCornerShape(1.dp))
            )
        }
    }
}

private val LiveRed = Color(0xFFD32F2F)

/**
 * Position and length; focusable, and left/right seek 10 s while it has focus (the controls
 * below use left/right to move between buttons).
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (deltaMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent {
                val code = it.nativeKeyEvent.keyCode
                val delta = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -SeekStepMs
                    KeyEvent.KEYCODE_DPAD_RIGHT -> SeekStepMs
                    else -> return@onPreviewKeyEvent false
                }
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onSeek(delta)
                true
            }
            .focusable()
    ) {
        val fraction =
            if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (isFocused) 8.dp else 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(
                        if (isFocused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(formatTime(positionMs), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.weight(1f))
            Text(formatTime(durationMs), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    /** Repeat when on: drawn in the accent colour. */
    active: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(if (large) 72.dp else 56.dp),
        colors = IconButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            contentColor = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(if (large) 40.dp else 28.dp),
        )
    }
}

private const val SeekStepMs = 10_000L

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

enum class QueueLoad { Loading, Error, Started }

@HiltViewModel
class MusicPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TakeoutMusicRepository,
    private val history: MusicHistory,
    val player: MusicPlayer,
) : ViewModel() {
    private val ref: QueueRef? =
        savedStateHandle.get<String>(MusicPlayerScreen.QueueBundleKey)?.let(QueueRef::parse)

    private val _loading = MutableStateFlow(QueueLoad.Loading)

    /** Loads the queue and hands it to the music service, once per visit. */
    val loading: StateFlow<QueueLoad> = _loading.asStateFlow()

    val nowPlaying: StateFlow<NowPlaying> = player.state

    val favoriteTrackEtags: StateFlow<Set<String>> = repository.favoriteTrackEtags

    fun toggleFavorite(etag: String, favorite: Boolean) {
        viewModelScope.launch { runCatching { repository.setTrackFavorite(etag, favorite) } }
    }

    init {
        load()
        viewModelScope.launch { repository.loadFavoriteTracks() }
    }

    fun load() {
        val ref = ref ?: run {
            _loading.value = QueueLoad.Error
            return
        }
        _loading.value = QueueLoad.Loading
        viewModelScope.launch {
            _loading.value = try {
                val queue = when (ref) {
                    is QueueRef.Album -> repository.albumQueue(ref.albumId, ref.startIndex)
                    is QueueRef.Artist ->
                        repository.artistQueue(ref.artistId, ref.mix, ref.startIndex)
                    is QueueRef.Station ->
                        repository.stationQueue(ref.stationId, ref.startIndex)
                    is QueueRef.Playlist ->
                        repository.playlistQueue(ref.playlistId, ref.startIndex)
                    is QueueRef.Favorites -> repository.favoritesQueue(ref.startIndex)
                    is QueueRef.History -> history.find(ref.entryId)?.toQueue()
                        ?: throw IllegalStateException("No such Recently Played entry")
                }
                if (queue.items.isEmpty()) {
                    QueueLoad.Error
                } else {
                    player.play(queue)
                    QueueLoad.Started
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                QueueLoad.Error
            }
        }
    }
}

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    val player: MusicPlayer,
    private val repository: TakeoutMusicRepository,
) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying> = player.state

    val favoriteTrackEtags: StateFlow<Set<String>> = repository.favoriteTrackEtags

    fun toggleFavorite(etag: String, favorite: Boolean) {
        viewModelScope.launch { runCatching { repository.setTrackFavorite(etag, favorite) } }
    }

    init {
        viewModelScope.launch { repository.loadFavoriteTracks() }
    }
}
