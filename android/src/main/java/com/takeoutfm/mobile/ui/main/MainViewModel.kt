package com.takeoutfm.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.media.NowPlaying
import com.takeoutfm.mobile.R
import com.takeoutfm.shared.music.ArtistMix
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.music.Track
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

/** Which artists the Artists tab lists; re-tapping the tab cycles them. */
enum class ArtistType { All, Favorite }

/**
 * State and actions shared across tabs: the music player (playing albums, mixes, stations,
 * playlists and Recently Played; play next / add to queue), track favorites, and the Artists
 * tab's type. Problems are reported through [messages] (string resources).
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val player: MusicPlayer,
    private val music: TakeoutMusicRepository,
    private val history: MusicHistory,
) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying> = player.state

    /** ETags of favorite tracks, for ♥ buttons. */
    val favoriteEtags: StateFlow<Set<String>> = music.favoriteTrackEtags

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

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
                if (queue.items.isEmpty()) _messages.tryEmit(R.string.nothing_to_play) else player.play(queue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(R.string.cant_play)
            }
        }
    }

    private val _artistType = MutableStateFlow(ArtistType.All)
    val artistType: StateFlow<ArtistType> = _artistType.asStateFlow()

    fun nextArtistType() = _artistType.update { ArtistType.entries[(it.ordinal + 1) % ArtistType.entries.size] }
}
