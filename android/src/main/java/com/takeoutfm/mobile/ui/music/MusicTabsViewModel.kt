package com.takeoutfm.mobile.ui.music

import androidx.lifecycle.ViewModel
import com.takeoutfm.mobile.ui.main.ArtistType
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.PlayedQueue
import com.takeoutfm.shared.music.Station
import com.takeoutfm.shared.music.StationGroup
import com.takeoutfm.shared.music.StationKind
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.util.listFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

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

    /** Live streams first, then the server's order; recent stations from Recently Played. */
    val radio: Flow<RadioPage> = combine(
        listFlow { music.radio().sortedBy { it.kind != StationKind.Stream } },
        history.entries,
    ) { groups, entries ->
        // A station's queue has the location /api/stations/<n>; its id is st-<n>.
        val byLocation = groups.flatMap { it.stations }
            .associateBy { "/api/stations/" + it.id.removePrefix(TakeoutMusicRepository.StationPrefix) }
        val recent = entries.mapNotNull { byLocation[it.location] }.distinct().take(RecentStations)
        RadioPage(recent, groups)
    }

    private companion object {
        const val RecentStations = 10
    }
}

/** The Radio tab: recently played stations, then every station by kind. */
data class RadioPage(val recent: List<Station>, val groups: List<StationGroup>)
