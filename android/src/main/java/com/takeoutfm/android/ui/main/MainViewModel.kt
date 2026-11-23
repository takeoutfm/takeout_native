package com.takeoutfm.android.ui.main

import com.takeoutfm.media.offline.CachePurge
import com.takeoutfm.media.offline.CacheUsage
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.media3.exoplayer.offline.DownloadService
import com.takeoutfm.media.offline.DownloadedItem
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.shared.music.QueueItem
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.viewModelScope
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.media.NowPlaying
import com.takeoutfm.android.R
import com.takeoutfm.shared.music.ArtistMix
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.MusicPlaylist
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.music.Track
import com.takeoutfm.shared.podcast.PodcastEpisode
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.video.VideoDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What "Add to playlist" adds. */
sealed interface AddTarget {
    data class TrackTarget(val trackId: String) : AddTarget
    data class AlbumTarget(val albumId: String) : AddTarget
}

/** Which artists the Artists tab lists; re-tapping the tab cycles them. */
enum class ArtistType { All, Favorite }

/**
 * State and actions shared across tabs: the music player (playing albums, mixes, stations,
 * playlists and Recently Played; play next / add to queue), track favorites, and the Artists
 * tab's type. Problems are reported through [messages] (string resources).
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaCache: TakeoutMediaCache,
    private val purge: CachePurge,
    private val player: MusicPlayer,
    private val music: TakeoutMusicRepository,
    private val history: MusicHistory,
    private val podcasts: TakeoutPodcastRepository,
) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying> = player.state

    /** Where the track is; read while playing only as long as a screen collects it. */
    val positionMs: StateFlow<Long> = player.positionMs

    /** ETags of favorite tracks, for ♥ buttons. */
    val favoriteEtags: StateFlow<Set<String>> = music.favoriteTrackEtags

    /** Tracks on the device (downloaded or downloading), by ETag. */
    val downloads: StateFlow<Map<String, DownloadedItem>> = mediaCache.items

    /** What Playback Caching keeps and the viewer downloaded, and whether space is short. */
    val cacheUsage: StateFlow<CacheUsage?> = purge.usage

    /** Recounts the store (and what's been watched) for the Downloads page. */
    fun refreshCacheUsage() = purge.refresh()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** A message with one argument, e.g. "Added to Road trip". */
    private val _formatted = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 4)
    val formatted: SharedFlow<Pair<Int, String>> = _formatted.asSharedFlow()

    /** What the "Add to playlist" picker is open for, or null. */
    private val _adding = MutableStateFlow<AddTarget?>(null)
    val adding: StateFlow<AddTarget?> = _adding.asStateFlow()

    private val pickerLoadFailed = MutableStateFlow(false)

    /**
     * The user's playlists for the picker: the core's (decision 017), which every edit keeps
     * current, loaded again when the picker opens. Empty if that failed with none known yet.
     */
    val playlists: StateFlow<List<MusicPlaylist>?> =
        combine(music.currentPlaylists, pickerLoadFailed) { list, failed -> list ?: if (failed) emptyList() else null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, music.currentPlaylists.value)

    fun addToPlaylist(target: AddTarget) {
        _adding.value = target
        pickerLoadFailed.value = false
        viewModelScope.launch { pickerLoadFailed.value = runCatching { music.playlists() }.isFailure }
    }

    fun dismissAdd() {
        _adding.value = null
    }

    /** Adds the picker's target to [playlist] (or to a new one named [newName]). */
    fun addTo(playlist: MusicPlaylist?, newName: String? = null) {
        val target = _adding.value ?: return
        _adding.value = null
        viewModelScope.launch {
            try {
                val list = playlist ?: music.createPlaylist(newName ?: return@launch)
                when (target) {
                    is AddTarget.TrackTarget -> music.addTrackToPlaylist(list.id, target.trackId)
                    is AddTarget.AlbumTarget -> music.addAlbumToPlaylist(list.id, target.albumId)
                }
                _formatted.tryEmit(R.string.added_to_playlist to list.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_save)
            }
        }
    }

    fun createPlaylist(name: String) = edit { music.createPlaylist(name) }
    fun deletePlaylist(playlistId: String) = edit { music.deletePlaylist(playlistId) }
    fun removeFromPlaylist(playlistId: String, index: Int) = edit { music.removeFromPlaylist(playlistId, index) }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block() // pages follow music.currentPlaylists
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_save)
            }
        }
    }

    init {
        // Show music that's already playing in the background.
        player.connect()
        viewModelScope.launch { runCatching { music.loadFavoriteTracks() } }
    }

    fun togglePlay() = player.playPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekBy(deltaMs: Long) = player.seekBy(deltaMs)
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun playAt(index: Int) = player.playAt(index)
    fun cycleRepeat() = player.cycleRepeat()

    fun playAlbum(albumId: String, index: Int = 0, shuffle: Boolean = false) = play {
        val queue = music.albumQueue(albumId, index)
        if (shuffle) queue.copy(items = queue.items.shuffled(), startIndex = 0) else queue
    }

    fun playArtist(artistId: String, mix: ArtistMix, index: Int = 0) =
        play { music.artistQueue(artistId, mix, index) }

    fun playStation(stationId: String) = play { music.stationQueue(stationId) }

    /** Plays a list of tracks as shown (e.g. Activity or Recent Tracks), from [index]. */
    fun playTracks(title: String, tracks: List<Track>, index: Int = 0, shuffle: Boolean = false) = play {
        val queue = music.tracksQueue(title, tracks, index)
        if (shuffle) queue.copy(items = queue.items.shuffled(), startIndex = 0) else queue
    }
    fun playTrack(trackId: String) = play { music.trackQueue(trackId) }
    fun playPlaylist(playlistId: String, index: Int = 0, shuffle: Boolean = false) = play {
        music.playlistQueue(playlistId, index).shuffledIf(shuffle)
    }
    fun playFavorites(index: Int = 0, shuffle: Boolean = false) = play { music.favoritesQueue(index).shuffledIf(shuffle) }

    private fun MusicQueue.shuffledIf(shuffle: Boolean) =
        if (shuffle) copy(items = items.shuffled(), startIndex = 0) else this

    /** Plays podcast [episodes] from [index], resuming that episode where it was left. */
    /** [episodes] from [index], resuming that one if it was started (unless [fromStart]). */
    fun playEpisodes(title: String, imageUri: String, episodes: List<PodcastEpisode>, index: Int, fromStart: Boolean = false) =
        play {
            podcasts.episodesQueue(title, imageUri, episodes, index)
                .let { if (fromStart) it.copy(startPositionMs = 0) else it }
        }

    /** Replays a Recently Played queue where it was left. */
    fun playRecent(entryId: String) = play {
        history.find(entryId)?.toQueue() ?: throw IllegalStateException("Gone from history")
    }

    /** Plays the track after the current one ([last]: at the end of the queue). */
    fun enqueueTrack(trackId: String, last: Boolean) {
        viewModelScope.launch {
            try {
                // Just the track: its playlist goes on with similar tracks.
                player.enqueue(music.trackQueue(trackId).items.take(1), last)
                _messages.tryEmit(if (last) R.string.added_to_queue else R.string.playing_next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_play)
            }
        }
    }

    fun setTrackFavorite(etag: String, favorite: Boolean) {
        viewModelScope.launch {
            try {
                music.setTrackFavorite(etag, favorite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_save)
            }
        }
    }

    private fun play(load: suspend () -> MusicQueue) {
        viewModelScope.launch {
            try {
                val queue = load()
                if (queue.items.isEmpty()) {
                    _messages.tryEmit(R.string.nothing_to_play)
                } else {
                    // Playback Caching keeps what plays; the music service lists whole tracks
                    // as downloads (nothing is fetched ahead).
                    player.play(queue)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_play)
            }
        }
    }

    fun downloadAlbum(albumId: String) = download { music.albumQueue(albumId) }
    fun downloadPlaylist(playlistId: String) = download { music.playlistQueue(playlistId) }
    fun downloadFavorites() = download { music.favoritesQueue() }

    fun downloadEpisode(title: String, imageUri: String, episodes: List<PodcastEpisode>, index: Int) = download {
        val queue = podcasts.episodesQueue(title, imageUri, episodes, index)
        queue.copy(items = listOfNotNull(queue.items.getOrNull(index)))
    }

    /** A video download that wouldn't leave enough free even with the cache emptied. */
    data class SpaceQuestion(val details: VideoDetails, val needBytes: Long, val freeableBytes: Long, val fits: Boolean)

    private val _spaceQuestion = MutableStateFlow<SpaceQuestion?>(null)

    /** Asks whether to download anyway ([answerSpace]); null when there's nothing to ask. */
    val spaceQuestion: StateFlow<SpaceQuestion?> = _spaceQuestion.asStateFlow()

    /**
     * Downloads a movie's or episode's file, to play offline in the video player. A file of known
     * size first gets room: cached tracks go if needed; if even that isn't enough, it asks
     * ([spaceQuestion]) unless [anyway].
     */
    fun downloadVideo(details: VideoDetails, anyway: Boolean = false) {
        if (details.etag.isEmpty() || details.videoUri.isEmpty() || details.etag in mediaCache.items.value) return
        viewModelScope.launch {
            if (!anyway && details.size > 0) {
                val room = purge.makeRoom(details.size)
                if (!room.enough) {
                    _spaceQuestion.value = SpaceQuestion(details, details.size, room.freeableBytes, room.fits)
                    return@launch
                }
            }
            startVideoDownload(details)
        }
    }

    /** The answer to [spaceQuestion]: download it anyway, or not. */
    fun answerSpace(download: Boolean) {
        val question = _spaceQuestion.value ?: return
        _spaceQuestion.value = null
        if (download) downloadVideo(question.details, anyway = true)
    }

    private fun startVideoDownload(details: VideoDetails) {
        val episode = details.episode
        val request = mediaCache.videoRequest(
            etag = details.etag,
            uri = details.videoUri,
            videoId = details.id,
            title = if (episode != null) "${episode.label} ${episode.name}".trim() else details.title,
            subtitle = episode?.seriesTitle ?: details.year,
            imageUri = details.backdropUri,
            // A series' episodes are listed together; each movie on its own.
            group = episode?.seriesId ?: details.id,
            groupTitle = episode?.seriesTitle ?: details.title,
        )
        DownloadService.sendAddDownload(context, com.takeoutfm.android.playback.DownloadService::class.java, request, false)
        _messages.tryEmit(R.string.download_started_video)
    }

    /** Plays downloaded [items] from [index] (works offline). */
    fun playDownloads(title: String, items: List<QueueItem>, index: Int) =
        play { MusicQueue(title = title, creator = "", imageUri = "", items = items, startIndex = index) }

    fun removeDownloads(etags: Collection<String>) {
        etags.forEach { DownloadService.sendRemoveDownload(context, com.takeoutfm.android.playback.DownloadService::class.java, it, false) }
    }

    private fun download(load: suspend () -> MusicQueue) {
        viewModelScope.launch {
            try {
                addDownloads(load(), notify = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_load)
            }
        }
    }

    private fun addDownloads(queue: MusicQueue, notify: Boolean) {
        val group = queue.location.ifEmpty { queue.title }
        // what's there already, unless only Playback Caching kept it: now it's the viewer's
        val requests = mediaCache.requests(queue.items, group, queue.title)
            .filter { request -> mediaCache.items.value[request.id].let { it == null || it.info.cached } }
        requests.forEach {
            DownloadService.sendAddDownload(context, com.takeoutfm.android.playback.DownloadService::class.java, it, false)
        }
        if (notify && requests.isNotEmpty()) _messages.tryEmit(R.string.download_started)
    }

    private val _artistType = MutableStateFlow(ArtistType.All)
    val artistType: StateFlow<ArtistType> = _artistType.asStateFlow()

    fun nextArtistType() = _artistType.update { ArtistType.entries[(it.ordinal + 1) % ArtistType.entries.size] }
}
