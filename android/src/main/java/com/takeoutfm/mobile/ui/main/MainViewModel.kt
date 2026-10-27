package com.takeoutfm.mobile.ui.main

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
import com.takeoutfm.mobile.R
import com.takeoutfm.shared.music.ArtistMix
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.MusicPlaylist
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.music.Track
import com.takeoutfm.shared.podcast.PodcastEpisode
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val player: MusicPlayer,
    private val music: TakeoutMusicRepository,
    private val history: MusicHistory,
    private val podcasts: TakeoutPodcastRepository,
) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying> = player.state

    /** ETags of favorite tracks, for ♥ buttons. */
    val favoriteEtags: StateFlow<Set<String>> = music.favoriteTrackEtags

    /** Tracks on the device (downloaded or downloading), by ETag. */
    val downloads: StateFlow<Map<String, DownloadedItem>> = mediaCache.items

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** A message with one argument, e.g. "Added to Road trip". */
    private val _formatted = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 4)
    val formatted: SharedFlow<Pair<Int, String>> = _formatted.asSharedFlow()

    /** What the "Add to playlist" picker is open for, or null. */
    private val _adding = MutableStateFlow<AddTarget?>(null)
    val adding: StateFlow<AddTarget?> = _adding.asStateFlow()

    /** The user's playlists for the picker (loaded when it opens). */
    private val _playlists = MutableStateFlow<List<MusicPlaylist>?>(null)
    val playlists: StateFlow<List<MusicPlaylist>?> = _playlists.asStateFlow()

    /** Bumped after a playlist changes, so pages showing playlists reload. */
    private val _playlistsChanged = MutableStateFlow(0)
    val playlistsChanged: StateFlow<Int> = _playlistsChanged.asStateFlow()

    fun addToPlaylist(target: AddTarget) {
        _adding.value = target
        _playlists.value = null
        viewModelScope.launch { _playlists.value = runCatching { music.playlists() }.getOrDefault(emptyList()) }
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
                _playlistsChanged.update { it + 1 }
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
                block()
                _playlistsChanged.update { it + 1 }
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
    fun playPlaylist(playlistId: String, index: Int = 0) = play { music.playlistQueue(playlistId, index) }
    fun playFavorites(index: Int = 0) = play { music.favoritesQueue(index) }

    /** Plays podcast [episodes] from [index], resuming that episode where it was left. */
    fun playEpisodes(title: String, imageUri: String, episodes: List<PodcastEpisode>, index: Int) =
        play { podcasts.episodesQueue(title, imageUri, episodes, index) }

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

    /** Plays downloaded [items] from [index] (works offline). */
    fun playDownloads(title: String, items: List<QueueItem>, index: Int) =
        play { MusicQueue(title = title, creator = "", imageUri = "", items = items, startIndex = index) }

    fun removeDownloads(etags: Collection<String>) {
        etags.forEach { DownloadService.sendRemoveDownload(context, com.takeoutfm.mobile.playback.DownloadService::class.java, it, false) }
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
        val requests = mediaCache.requests(queue.items, group, queue.title)
            .filter { it.id !in mediaCache.items.value }
        requests.forEach {
            DownloadService.sendAddDownload(context, com.takeoutfm.mobile.playback.DownloadService::class.java, it, false)
        }
        if (notify && requests.isNotEmpty()) _messages.tryEmit(R.string.download_started)
    }

    private val _artistType = MutableStateFlow(ArtistType.All)
    val artistType: StateFlow<ArtistType> = _artistType.asStateFlow()

    fun nextArtistType() = _artistType.update { ArtistType.entries[(it.ordinal + 1) % ArtistType.entries.size] }
}
