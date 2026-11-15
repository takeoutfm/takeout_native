package com.takeoutfm.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.music.MusicHistory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the music player is doing, for the Now Playing screen and the mini player. */
data class NowPlaying(
    val items: List<QueueItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    /** Buffering or about to play (so play/pause shows "pause"). */
    val playWhenReady: Boolean = false,
    /** The track's length; where it is, which changes by itself, is [MusicPlayer.positionMs]. */
    val durationMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Why the current track can't play, or null. */
    val error: String? = null,
    /** A live stream (Live Radio): no ETag and no length, so no seeking or skipping. */
    val isLive: Boolean = false,
    /** What a live stream says is playing now (its ICY metadata), or null. */
    val streamTitle: String? = null,
) {
    val current: QueueItem? get() = items.getOrNull(index)

    /** The current item's ETag and the next one's (which the player may already read ahead). */
    val loadedEtags: Set<String> get() = listOfNotNull(current, items.getOrNull(index + 1)).map { it.etag }.filter { it.isNotEmpty() }.toSet()
    val hasQueue: Boolean get() = items.isNotEmpty()
}

/**
 * The UI's handle on the app's [TakeoutMusicService] ([service]): connects a [MediaController]
 * on first use, plays queues and exposes [state]. Everything runs on the main thread, as
 * MediaController requires.
 */
class MusicPlayer(
    private val context: Context,
    session: TakeoutSession,
    private val history: MusicHistory,
    private val service: Class<out MediaSessionService>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectLock = Mutex()
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(NowPlaying())

    /** What's playing; changes with the player's events (never polled, so following it costs nothing). */
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)

    /**
     * Where the track is. It changes by itself while playing, with no event, so it's read
     * every half second, but only while playing and something collects this (a Now Playing
     * screen or progress ring on view); otherwise it's taken at each player event.
     */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    init {
        scope.launch {
            combine(
                _state.map { it.isPlaying }.distinctUntilChanged(),
                _positionMs.subscriptionCount.map { it > 0 }.distinctUntilChanged(),
            ) { playing, watched -> playing && watched }
                .distinctUntilChanged()
                .collectLatest { poll ->
                    while (poll) {
                        controller?.let { _positionMs.value = it.currentPosition }
                        delay(500)
                    }
                }
        }
        // Signing out stops the music (its streams belong to the old session).
        scope.launch {
            session.state.collect { if (!it.isLoggedIn) stop() }
        }
    }

    /**
     * Replaces the queue with [queue] and plays from its start (index and position), as a
     * Recently Played entry (the service's [HistoryProgress] keeps where it got to).
     */
    fun play(queue: MusicQueue) {
        val historyId = history.record(queue)
        scope.launch {
            val c = controller() ?: return@launch
            c.setMediaItems(
                queue.items.map { it.toMediaItem(historyId) },
                queue.startIndex,
                queue.startPositionMs,
            )
            c.prepare()
            c.play()
        }
    }

    fun playPause() = withController {
        if (it.playWhenReady) {
            it.pause()
        } else if (_state.value.isLive) {
            // Rejoin the stream live rather than resuming stale buffered audio.
            it.stop()
            it.prepare()
            it.play()
        } else {
            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0, 0L)
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }

    /** Connects to the music service, so [state] shows what's already playing in the background. */
    fun connect() = withController { }

    /** Connects (if not yet) and waits for it; true once [state] reflects the service. */
    suspend fun awaitConnected(): Boolean = withContext(Dispatchers.Main.immediate) { controller() != null }

    /**
     * Plays [items] after the current item (or starts them when nothing's queued); with
     * [last], adds them to the end instead.
     */
    fun enqueue(items: List<QueueItem>, last: Boolean = false) = withController {
        if (items.isEmpty()) return@withController
        val media = items.map { item -> item.toMediaItem() }
        if (it.mediaItemCount == 0) {
            it.setMediaItems(media)
            it.prepare()
            it.play()
        } else {
            it.addMediaItems(if (last) it.mediaItemCount else it.currentMediaItemIndex + 1, media)
        }
    }

    /** To the start of the track, or the previous one when already near its start. */
    fun previous() = withController { it.seekToPrevious() }

    fun seekBy(deltaMs: Long) = withController {
        val duration = it.duration.takeIf { d -> d != C.TIME_UNSET }
        val target = (it.currentPosition + deltaMs).coerceAtLeast(0)
        it.seekTo(if (duration != null) target.coerceAtMost(duration) else target)
    }

    fun playAt(index: Int) = withController {
        it.seekTo(index, 0L)
        it.play()
    }

    /** Off → all → one → off. */
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch { controller()?.let(block) }
    }

    private suspend fun controller(): MediaController? = connectLock.withLock {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, service))
        val c = try {
            MediaController.Builder(context, token).buildAsync().await()
        } catch (e: Exception) {
            return null
        }
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = update(player)

            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(error = error.errorCodeName)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _state.value = _state.value.copy(error = null)
            }
        })
        controller = c
        update(c)
        c
    }

    private fun update(player: Player) {
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toQueueItem() }
        val current = items.getOrNull(player.currentMediaItemIndex)
        val isLive = current != null && current.etag.isEmpty() &&
            (player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET)
        // Put there by TakeoutMusicService from the stream's ICY metadata.
        val streamTitle = player.currentMediaItem?.mediaMetadata?.extras
            ?.getString(StreamTitleKey)
            ?.takeIf { isLive && it.isNotBlank() }
        _positionMs.value = player.currentPosition
        _state.value = _state.value.copy(
            isLive = isLive,
            streamTitle = streamTitle,
            items = items,
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            repeatMode = player.repeatMode,
        )
    }
}

/**
 * The stream URI also goes in the request metadata, since Media3 doesn't pass a controller's
 * `localConfiguration` to the session ([TakeoutMusicService] restores it). The ETag is the media id;
 * [historyId] is the Recently Played entry the item's queue was recorded as.
 */
internal fun QueueItem.toMediaItem(historyId: String? = null): MediaItem =
    MediaItem.Builder()
        .setMediaId(etag)
        .setUri(uri)
        // Kept on the device by ETag (see TakeoutMediaCache); streams have none.
        .setCustomCacheKey(etag.ifEmpty { null })
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(imageUri.ifEmpty { null }?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(
                    Bundle().apply {
                        putString(BackgroundKey, backgroundUri)
                        putBoolean(PodcastKey, podcast)
                        historyId?.let { putString(HistoryKey, it) }
                    }
                )
                .build()
        )
        .build()

/** The artist background's URL in [MediaMetadata.extras] (Media3 has no field for it). */
private const val BackgroundKey = "takeout.background"

/** True in [MediaMetadata.extras] for a podcast episode (progress, not a listen). */
internal const val PodcastKey = "takeout.podcast"

/** The Recently Played entry id in [MediaMetadata.extras] (see [HistoryProgress]). */
internal const val HistoryKey = "takeout.history"

internal val MediaItem.historyId: String? get() = mediaMetadata.extras?.getString(HistoryKey)

/** A podcast episode, by its extras. */
internal val MediaItem.isPodcast: Boolean get() = mediaMetadata.extras?.getBoolean(PodcastKey) == true

/** A live stream's current "now playing" text in [MediaMetadata.extras]. */
internal const val StreamTitleKey = "takeout.streamTitle"

internal fun MediaItem.toQueueItem(): QueueItem = QueueItem(
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    album = mediaMetadata.albumTitle?.toString().orEmpty(),
    imageUri = mediaMetadata.artworkUri?.toString().orEmpty(),
    backgroundUri = mediaMetadata.extras?.getString(BackgroundKey).orEmpty(),
    uri = requestMetadata.mediaUri?.toString().orEmpty(),
    etag = mediaId,
    podcast = isPodcast,
)

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWithException(e.cause ?: e)
        }
    }, MoreExecutors.directExecutor())
    cont.invokeOnCancellation { cancel(false) }
}
