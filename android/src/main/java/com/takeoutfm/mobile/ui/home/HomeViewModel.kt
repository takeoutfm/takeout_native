package com.takeoutfm.mobile.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Tile
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.library.LibraryIndex
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.music.Album
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.video.TakeoutVideoRepository
import com.takeoutfm.shared.video.VideoCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Tile ids for genres (they open a genre page). */
const val GenrePrefix = "g-"

/** What Home shows; the media bar picks the type, tapping it again cycles its [subtypes]. */
enum class MediaType(@StringRes val title: Int, val subtypes: List<Subtype>) {
    Music(R.string.nav_music, listOf(Subtype.MusicAdded, Subtype.MusicRecent)),
    Film(
        R.string.nav_movies,
        listOf(Subtype.FilmAdded, Subtype.FilmRecent, Subtype.FilmWatched, Subtype.FilmRecommended, Subtype.FilmGenres, Subtype.FilmFavorite),
    ),
    Tv(R.string.nav_tv_shows, listOf(Subtype.TvAdded, Subtype.TvAll)),
}

enum class Subtype(@StringRes val label: Int) {
    MusicAdded(R.string.music_added),
    MusicRecent(R.string.music_recent),
    FilmAdded(R.string.film_added),
    FilmRecent(R.string.film_recent),
    FilmWatched(R.string.film_watched),
    FilmRecommended(R.string.film_recommended),
    FilmGenres(R.string.film_genres),
    FilmFavorite(R.string.film_favorite),
    TvAdded(R.string.tv_added),
    TvAll(R.string.tv_all),
}

data class HomeSelection(val type: MediaType = MediaType.Music, val subtype: Subtype = Subtype.MusicAdded)

data class HomeContent(val tiles: List<Tile> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: TakeoutLibrary,
    private val music: TakeoutMusicRepository,
    private val video: TakeoutVideoRepository,
    private val session: TakeoutSession,
) : ViewModel() {
    /** The media types this server's library has (all, until the index is known). */
    val types: StateFlow<List<MediaType>> = library.index
        .map { available(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, available(library.index.value))

    private val chosen = MutableStateFlow(HomeSelection())

    /** The chosen type and subtype, kept to what the library has. */
    val selection: StateFlow<HomeSelection> = combine(chosen, types) { s, t ->
        if (s.type in t || t.isEmpty()) s else HomeSelection(t.first(), t.first().subtypes.first())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeSelection())

    private val reloads = MutableStateFlow(0)

    val content: StateFlow<HomeContent> = combine(selection, reloads) { s, _ -> s }
        .flatMapLatest { s ->
            flow {
                emit(HomeContent(loading = true))
                emit(
                    try {
                        HomeContent(load(s.subtype), loading = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        HomeContent(loading = false, failed = true)
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeContent())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** A media bar button: picks the type, or cycles its subtypes when it's already shown. */
    fun select(type: MediaType) = chosen.update { current ->
        if (selection.value.type == type) {
            val next = (type.subtypes.indexOf(selection.value.subtype) + 1) % type.subtypes.size
            HomeSelection(type, type.subtypes[next])
        } else {
            HomeSelection(type, type.subtypes.first())
        }
    }

    /** Re-tapping the Home tab: the next media type the library has. */
    fun nextMediaType() {
        val t = types.value
        if (t.isEmpty()) return
        val next = t[(t.indexOf(selection.value.type) + 1) % t.size]
        chosen.value = HomeSelection(next, next.subtypes.first())
    }

    /** Pull to refresh / Reload: a fresh library index, then the current list again. */
    fun reload() {
        viewModelScope.launch {
            _refreshing.value = true
            library.refresh()
            reloads.update { it + 1 }
            _refreshing.value = false
        }
    }

    fun logout() = session.signOut()

    private suspend fun load(subtype: Subtype): List<Tile> = when (subtype) {
        Subtype.MusicAdded -> music.recentlyAdded().first().map { it.toTile() }
        Subtype.MusicRecent -> music.newReleases().first().map { it.toTile() }
        Subtype.FilmAdded -> video.recentlyAdded().map { it.toTile() }
        Subtype.FilmRecent -> video.newReleases().map { it.toTile() }
        Subtype.FilmWatched -> video.continueWatching(ProgressRules.AllTime).map { it.toTile() }
        Subtype.FilmRecommended -> video.recommended(0).map { it.toTile() }
        Subtype.FilmGenres -> video.genres().map { Tile("$GenrePrefix${it.id}", it.name, "", "") }
        Subtype.FilmFavorite -> video.favorites().map { it.toTile() }
        Subtype.TvAdded -> video.recentShows().map { it.toTile() }
        Subtype.TvAll -> video.shows().map { it.toTile() }
    }

    private fun Album.toTile() = Tile(id, name, artist, coverUri)

    private fun VideoCard.toTile() = Tile(id, title, "", posterUri, poster = true)

    private fun available(index: LibraryIndex?): List<MediaType> = if (index == null) {
        MediaType.entries
    } else {
        buildList {
            if (index.hasMusic) add(MediaType.Music)
            if (index.hasMovies) add(MediaType.Film)
            if (index.hasShows) add(MediaType.Tv)
        }
    }
}
