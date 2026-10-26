package com.takeoutfm.media

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.takeoutfm.media.auto.TakeoutBrowseTree
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.progress.TakeoutProgress

/**
 * Plays music in the background: owns the music player and its queue, so playback continues
 * while browsing (or with the app in the background), and exposes a media library session so
 * remote keys, Assistant, the system's now-playing controls and Android Auto (browsing
 * [browseTree]) work anywhere. The UI talks to it
 * through [MusicPlayer]. Video playback stays screen-scoped and separate.
 *
 * Each app subclasses it (declared in its manifest) to supply its singletons and the screen
 * that opening the session returns to; they're used from [onCreate] on, after injection.
 */
abstract class TakeoutMusicService : MediaLibraryService() {
    protected abstract val playerFactory: TakeoutPlayerFactory
    protected abstract val listenReporter: ListenReporter

    /** Recently Played, which the playing queue's position is saved into. */
    protected abstract val history: MusicHistory

    /** Where songs heard on live streams are recorded; null to not keep them. */
    protected open val streamHistory: StreamHistory? = null

    /** Tracks kept on the device (downloads, Playback Caching); null to always stream. */
    protected open val mediaCache: TakeoutMediaCache? = null

    /** Where podcast episodes' positions are saved; null to not save them. */
    protected open val progress: TakeoutProgress? = null

    /** What Android Auto and other media browsers can browse; null for none (the TV). */
    protected open val browseTree: TakeoutBrowseTree? = null

    /** App-specific player options (e.g. Wear OS's output checks), applied last. */
    protected open fun configure(builder: ExoPlayer.Builder): ExoPlayer.Builder = builder

    /** The activity the system's now-playing controls open. */
    protected abstract val sessionActivity: Class<out Activity>

    private var session: MediaLibrarySession? = null
    private var listenTracker: ListenTracker? = null
    private var podcastProgress: PodcastProgress? = null
    private var historyProgress: HistoryProgress? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = playerFactory.builder(mediaCache)
            // Music: pauses for calls, other apps' audio and video in this app; resumes after
            // short interruptions.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // Podcasts' seek buttons (PodcastButtons).
            .setSeekBackIncrementMs(SeekBackMs)
            .setSeekForwardIncrementMs(SeekForwardMs)
            .let(::configure)
            .build()
            .also { playerFactory.addDebugLogging(it) }
        // Reports plays to Takeout / ListenBrainz per the Music settings.
        listenTracker = ListenTracker(player, listenReporter).also { it.attach() }
        player.addListener(StreamTitles(player, streamHistory))
        historyProgress = HistoryProgress(player, history).also { it.attach() }
        progress?.let { podcastProgress = PodcastProgress(player, it).also { p -> p.attach() } }

        // Opening the session from the system's now-playing controls returns to the app.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, sessionActivity)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setId("music")
            .setSessionActivity(openApp)
            .build()
            .also { player.addListener(PodcastButtons(it)) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Nothing playing when the app is swiped away: stop rather than linger. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        listenTracker?.detach()
        podcastProgress?.detach()
        historyProgress?.detach()
        historyProgress = null
        listenTracker = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * A live stream's "now playing" text (ICY metadata) only reaches listeners on the player
     * itself, and the item's own title outranks it in the merged metadata; so copy it into the
     * playing item's metadata extras, where [MusicPlayer] (and its controller) sees it. A
     * metadata-only replace doesn't interrupt playback.
     */
    @OptIn(UnstableApi::class)
    private class StreamTitles(
        private val player: ExoPlayer,
        private val history: StreamHistory?,
    ) : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            val title = (0 until metadata.length())
                .map { metadata[it] }
                .filterIsInstance<IcyInfo>()
                .firstNotNullOfOrNull { it.title?.trim()?.takeIf { t -> t.isNotEmpty() } }
                ?: return
            val index = player.currentMediaItemIndex
            val item = player.currentMediaItem ?: return
            if (item.mediaMetadata.extras?.getString(StreamTitleKey) == title) return
            history?.heard(
                station = item.mediaMetadata.title?.toString().orEmpty(),
                imageUri = item.mediaMetadata.artworkUri?.toString().orEmpty(),
                title = title,
            )
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                putString(StreamTitleKey, title)
            }
            player.replaceMediaItem(
                index,
                item.buildUpon()
                    .setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setExtras(extras)
                            // Shown by the system's now-playing controls.
                            .setSubtitle(title)
                            .build()
                    )
                    .build()
            )
        }
    }

    private val seekBack = SessionCommand(SeekBackAction, Bundle.EMPTY)
    private val seekForward = SessionCommand(SeekForwardAction, Bundle.EMPTY)

    /** Back 10 s and forward 30 s in place of previous / next, for podcast episodes. */
    private fun mediaButtons(podcast: Boolean): List<CommandButton> = if (!podcast) emptyList() else listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setSessionCommand(seekBack)
            .setDisplayName(getString(R.string.seek_back))
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setSessionCommand(seekForward)
            .setDisplayName(getString(R.string.seek_forward))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    private val sessionCommands: SessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(seekBack)
            .add(seekForward)
            .build()

    /** Without previous / next for a podcast episode, so its seek buttons take their places. */
    private fun playerCommands(podcast: Boolean): Player.Commands =
        if (!podcast) {
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
        } else {
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                )
                .build()
        }

    /**
     * The system's controls (the notification, which the platform session and so Android Auto
     * follow) and other apps' controllers get [mediaButtons] / [playerCommands]; the app's own
     * player keeps every command.
     */
    private fun MediaSession.followsPodcastButtons(controller: MediaSession.ControllerInfo) =
        isMediaNotificationController(controller) || controller.packageName != packageName

    private val Player.playingPodcast: Boolean get() = currentMediaItem?.isPodcast == true

    /** Switches the seek buttons on and off as the playing item becomes, or stops being, a podcast. */
    private inner class PodcastButtons(private val session: MediaSession) : Player.Listener {
        private var podcast = false

        override fun onEvents(player: Player, events: Player.Events) {
            if (!events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) return
            val now = player.playingPodcast
            if (now == podcast) return
            podcast = now
            session.connectedControllers.filter { session.followsPodcastButtons(it) }.forEach {
                session.setAvailableCommands(it, sessionCommands, playerCommands(now))
            }
            session.setMediaButtonPreferences(mediaButtons(now))
        }
    }

    /**
     * The library session's answers: browsing and search from [browseTree] (empty without one),
     * and playing a browsed item expands it into its whole queue. Items from the app's own
     * controller arrive without their stream URI (Media3 doesn't pass `localConfiguration` to the
     * session); [MusicPlayer] puts it in the request metadata, and this restores it.
     */
    @OptIn(UnstableApi::class)
    private inner class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val podcast = session.player.playingPodcast
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .apply { if (session.followsPodcastButtons(controller)) setAvailablePlayerCommands(playerCommands(podcast)) }
                .setMediaButtonPreferences(mediaButtons(podcast))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SeekBackAction -> session.player.seekBack()
                SeekForwardAction -> session.player.seekForward()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val tree = browseTree ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
            return Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val tree = browseTree ?: return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
            try {
                val all = tree.children(parentId)
                val from = (page * pageSize).coerceAtMost(all.size)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val tree = browseTree ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
            scope.launch {
                val count = runCatching { tree.search(query).size }.getOrDefault(0)
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val tree = browseTree ?: return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
            try {
                val all = tree.search(query)
                val from = (page * pageSize).coerceAtMost(all.size)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val tree = browseTree
            val single = mediaItems.singleOrNull()
            // A browsed item, or "play …" by voice: the queue it stands for.
            val queue = if (tree != null && single != null) {
                runCatching {
                    tree.queue(single.mediaId)
                        ?: single.requestMetadata.searchQuery?.let { q -> tree.searchQueue(q) }
                }.getOrNull()
            } else {
                null
            }
            if (queue != null && tree != null) {
                MediaSession.MediaItemsWithStartPosition(tree.startPlaying(queue), queue.startIndex, queue.startPositionMs)
            } else {
                MediaSession.MediaItemsWithStartPosition(withUris(mediaItems), startIndex, startPositionMs)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(withUris(mediaItems))

        private fun withUris(items: List<MediaItem>): MutableList<MediaItem> = items.map { item ->
            val uri = item.requestMetadata.mediaUri
            if (item.localConfiguration == null && uri != null) item.buildUpon().setUri(uri).build() else item
        }.toMutableList()
    }
}

private const val SeekBackMs = 10_000L
private const val SeekForwardMs = 30_000L
private const val SeekBackAction = "takeout.SEEK_BACK"
private const val SeekForwardAction = "takeout.SEEK_FORWARD"
