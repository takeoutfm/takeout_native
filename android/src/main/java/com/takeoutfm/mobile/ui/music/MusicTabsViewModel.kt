package com.takeoutfm.mobile.ui.music

import androidx.lifecycle.ViewModel
import com.takeoutfm.mobile.ui.main.ArtistType
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.PlayedQueue
import com.takeoutfm.shared.music.StationGroup
import com.takeoutfm.shared.music.StationKind
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.util.listFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class MusicTabsViewModel @Inject constructor(
    private val music: TakeoutMusicRepository,
    history: MusicHistory,
) : ViewModel() {
    fun artists(type: ArtistType): Flow<List<Artist>> = when (type) {
        ArtistType.All -> music.artists()
        ArtistType.Favorite -> listFlow { music.favorites().artists }
    }

    val recentlyPlayed: StateFlow<List<PlayedQueue>> = history.entries

    /** Live streams first, then the server's order. */
    val radio: Flow<List<StationGroup>> = listFlow {
        music.radio().sortedBy { it.kind != StationKind.Stream }
    }
}
