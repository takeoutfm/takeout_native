package com.takeoutfm.android.ui.music

import androidx.lifecycle.ViewModel
import com.takeoutfm.android.ui.main.ArtistType
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.PlayedQueue
import com.takeoutfm.shared.music.Station
import com.takeoutfm.shared.music.StationGroup
import com.takeoutfm.shared.music.RadioNow
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.util.listFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

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

    /** Live stations, then streams; recent stations from Recently Played. */
    val radio: Flow<RadioPage> = combine(
        listFlow { music.radio() },
        history.entries,
    ) { groups, entries ->
        // A station's queue has the location /api/radio/<n>; its id is st-<n>.
        val byLocation = groups.flatMap { it.stations }
            .associateBy { "/api/radio/" + it.id.removePrefix(TakeoutMusicRepository.StationPrefix) }
        val recent = entries.mapNotNull { byLocation[it.location] }.distinct().take(RecentStations)
        RadioPage(recent, groups)
    }

    /**
     * What's on each live station, fetched again when the first of those tracks ends (or a
     * minute after a failure), while the Radio tab is shown.
     */
    val radioNow: Flow<RadioNow?> = flow {
        while (true) {
            val now = try {
                music.radioNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            emit(now)
            val wait = now?.refreshAtMs?.takeIf { it > 0 }?.let { it - System.currentTimeMillis() } ?: RetryMs
            delay(wait.coerceIn(MinWaitMs, MaxWaitMs))
        }
    }

    private companion object {
        const val RecentStations = 10
        const val RetryMs = 60_000L
        const val MinWaitMs = 2_000L
        const val MaxWaitMs = 10 * 60_000L
    }
}

/** The Radio tab: recently played stations, then every station by kind. */
data class RadioPage(val recent: List<Station>, val groups: List<StationGroup>)
