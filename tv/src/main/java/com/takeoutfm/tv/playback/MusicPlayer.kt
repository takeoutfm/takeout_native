package com.takeoutfm.tv.playback

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
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.takeoutfm.tv.data.entities.music.MusicQueue
import com.takeoutfm.tv.data.entities.music.QueueItem
import com.takeoutfm.tv.data.music.MusicHistory
import com.takeoutfm.tv.data.takeout.TakeoutSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the music player is doing, for the Now Playing screen and the mini player. */
data class NowPlaying(
    val items: List<QueueItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    /** Buffering or about to play (so play/pause shows "pause"). */
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0,
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
    val hasQueue: Boolean get() = items.isNotEmpty()
}

/**
 * The UI's handle on [MusicService]: connects a [MediaController] on first use, plays queues
 * and exposes [state]. Everything runs on the main thread, as MediaController requires.
 */
@Singleton
class MusicPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    session: TakeoutSession,
    private val history: MusicHistory,
) {
    /** Recently Played entry of the queue playing now, updated with where it gets to. */
    private var historyId: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectLock = Mutex()
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    init {
        // Signing out stops the music (its streams belong to the old session).
        scope.launch {
            session.state.collect { if (!it.isLoggedIn) stop() }
        }
    }

    /** Replaces the queue with [queue] and plays from its start (index and position). */
    fun play(queue: MusicQueue) {
        historyId = history.record(queue)
        scope.launch {
            val c = controller() ?: return@launch
            c.setMediaItems(
                queue.items.map { it.toMediaItem() },
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
        historyId = null
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch { controller()?.let(block) }
    }

    private suspend fun controller(): MediaController? = connectLock.withLock {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
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
                saveProgress(c)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker(player = c) else ticker?.cancel()
                saveProgress(c)
            }
        })
        controller = c
        update(c)
        if (c.isPlaying) startTicker(c)
        c
    }

    /** Position only changes by itself, so poll it while playing. */
    private fun startTicker(player: Player) {
        ticker?.cancel()
        var ticks = 0
        ticker = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(
                    positionMs = player.currentPosition,
                    durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                )
                if (++ticks % ProgressEveryTicks == 0) saveProgress(player)
                delay(500)
            }
        }
    }

    /** Records where the queue got to, so Recently Played resumes there. */
    private fun saveProgress(player: Player) {
        val id = historyId ?: return
        if (player.mediaItemCount == 0) return
        history.progress(id, player.currentMediaItemIndex, player.currentPosition)
    }

    private fun update(player: Player) {
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toQueueItem() }
        val current = items.getOrNull(player.currentMediaItemIndex)
        val isLive = current != null && current.etag.isEmpty() &&
            (player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET)
        // Put there by MusicService from the stream's ICY metadata.
        val streamTitle = player.currentMediaItem?.mediaMetadata?.extras
            ?.getString(StreamTitleKey)
            ?.takeIf { isLive && it.isNotBlank() }
        _state.value = _state.value.copy(
            isLive = isLive,
            streamTitle = streamTitle,
            items = items,
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            repeatMode = player.repeatMode,
        )
    }
}

/** Save the position every this many ticker ticks (500 ms each) while playing. */
private const val ProgressEveryTicks = 20

/**
 * The stream URI also goes in the request metadata, since Media3 doesn't pass a controller's
 * `localConfiguration` to the session ([MusicService] restores it). The ETag is the media id.
 */
internal fun QueueItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(etag)
        .setUri(uri)
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
                .setExtras(Bundle().apply { putString(BackgroundKey, backgroundUri) })
                .build()
        )
        .build()

/** The artist background's URL in [MediaMetadata.extras] (Media3 has no field for it). */
private const val BackgroundKey = "takeout.background"

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
