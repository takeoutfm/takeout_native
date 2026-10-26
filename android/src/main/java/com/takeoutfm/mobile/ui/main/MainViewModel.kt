package com.takeoutfm.mobile.ui.main

import androidx.lifecycle.ViewModel
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.media.NowPlaying
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Which artists the Artists tab lists; re-tapping the tab cycles them. */
enum class ArtistType { All, Favorite }

/** State shared across tabs: the music player and the Artists tab's type. */
@HiltViewModel
class MainViewModel @Inject constructor(private val player: MusicPlayer) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying> = player.state

    fun togglePlay() = player.playPause()

    private val _artistType = MutableStateFlow(ArtistType.All)
    val artistType: StateFlow<ArtistType> = _artistType.asStateFlow()

    fun nextArtistType() = _artistType.update { ArtistType.entries[(it.ordinal + 1) % ArtistType.entries.size] }
}
