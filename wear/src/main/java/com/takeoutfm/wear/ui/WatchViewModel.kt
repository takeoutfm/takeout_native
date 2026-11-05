package com.takeoutfm.wear.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.DownloadService
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.media.NowPlaying
import com.takeoutfm.media.offline.DownloadedItem
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.shared.api.SessionState
import com.takeoutfm.shared.api.DeviceCode
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.PlayedQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.settings.PlaySettings
import com.takeoutfm.wear.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sign-in by code on the watch: the code to approve on the phone (Link) or the web
 * ([DeviceCode.link]), and whether it [expired] (its time ran out, or the server forgot it).
 */
data class CodeState(
    val code: DeviceCode? = null,
    val expired: Boolean = false,
    val busy: Boolean = false,
    val error: Boolean = false,
)

/** Everything the watch screens share: session, library, player, downloads, settings. */
@HiltViewModel
class WatchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: TakeoutApi,
    private val session: TakeoutSession,
    private val library: TakeoutLibrary,
    val music: TakeoutMusicRepository,
    val podcasts: TakeoutPodcastRepository,
    private val history: MusicHistory,
    private val player: MusicPlayer,
    private val mediaCache: TakeoutMediaCache,
    val settings: PlaySettings,
) : ViewModel() {
    val session_: StateFlow<SessionState> = session.state
    val endpoint: String get() = session.endpoint
    val nowPlaying: StateFlow<NowPlaying> = player.state
    val recent: StateFlow<List<PlayedQueue>> = history.entries
    val downloads: StateFlow<Map<String, DownloadedItem>> = mediaCache.items

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    init {
        player.connect()
    }

    // ---- Sign-in by code ----

    private val _code = MutableStateFlow(CodeState())
    val code: StateFlow<CodeState> = _code.asStateFlow()
    private var codeJob: Job? = null

    fun getCode(host: String) {
        val endpoint = TakeoutApi.normalizeEndpoint(host)
        codeJob?.cancel()
        codeJob = viewModelScope.launch {
            _code.value = CodeState(busy = true)
            val code = try {
                api.requestCode(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch run { _code.value = CodeState(error = true) }
            }
            _code.value = CodeState(code = code)
            val tokens = api.awaitCode(code) ?: return@launch _code.update { it.copy(expired = true) }
            _code.value = CodeState() // not shown again after a sign-out
            session.signIn(endpoint, tokens)
            runCatching { library.refresh() }
        }
    }

    fun signOut() = session.signOut()

    // ---- Playback ----

    fun togglePlay() = player.playPause()
    fun next() = player.next()
    fun previous() = player.previous()

    fun play(load: suspend () -> MusicQueue) {
        viewModelScope.launch {
            try {
                val queue = load()
                if (queue.items.isNotEmpty()) {
                    player.play(queue)
                    if (settings.playbackCaching.value) addDownloads(queue)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_play)
            }
        }
    }

    fun playRecent(id: String) = play { history.find(id)?.toQueue() ?: error("gone") }

    fun playDownloads(items: List<QueueItem>, index: Int) =
        play { MusicQueue(context.getString(R.string.downloads), "", "", items, startIndex = index) }

    // ---- Downloads ----

    fun download(load: suspend () -> MusicQueue) {
        viewModelScope.launch {
            try {
                addDownloads(load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_load)
            }
        }
    }

    private fun addDownloads(queue: MusicQueue) {
        mediaCache.requests(queue.items, queue.location.ifEmpty { queue.title }, queue.title)
            .filter { it.id !in mediaCache.items.value }
            .forEach { DownloadService.sendAddDownload(context, com.takeoutfm.wear.playback.DownloadService::class.java, it, false) }
    }

    fun removeDownloads(etags: Collection<String>) = etags.forEach {
        DownloadService.sendRemoveDownload(context, com.takeoutfm.wear.playback.DownloadService::class.java, it, false)
    }
}
