package com.takeoutfm.shared.music

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.model.ApiArtist
import com.takeoutfm.shared.model.ApiPlaylist
import com.takeoutfm.shared.model.ApiRelease
import com.takeoutfm.shared.model.ApiStation
import com.takeoutfm.shared.model.ApiTrack
import com.takeoutfm.shared.util.listFlow
import com.takeoutfm.shared.util.sortedByTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * The server's music library for the music screens. Ids are "ar-<id>" (artists), "al-<id>"
 * (releases) and "t-<id>" (tracks); play requests return a [MusicQueue] from the server's spiff
 * playlists.
 */
class TakeoutMusicRepository(
    private val api: TakeoutApi,
    private val library: TakeoutLibrary,
    private val urls: TakeoutUrls,
) {
    // ---- Home (shares /api/home with the video Home) ----

    fun recentlyAdded(): Flow<List<Album>> = listFlow {
        library.home().addedReleases.orEmpty().map { it.toAlbum() }
    }

    fun newReleases(): Flow<List<Album>> = listFlow {
        library.home().newReleases.orEmpty().map { it.toAlbum() }
    }

    // ---- Artists ----

    private val artistsLock = Mutex()
    private var artistsCache: Pair<Long, List<Artist>>? = null

    /** Every artist A–Z by sort name, kept for a few minutes (the list is large). */
    fun artists(): Flow<List<Artist>> = listFlow { loadArtists() }

    private suspend fun loadArtists(): List<Artist> = artistsLock.withLock {
        val cached = artistsCache
        val now = Clock.System.now().toEpochMilliseconds()
        if (cached != null && now - cached.first < ArtistsMaxAgeMs) {
            return@withLock cached.second
        }
        val list = api.artists().artists.orEmpty()
            .sortedByTitle { it.sortName.ifEmpty { it.name } }
            .map { it.toArtist() }
        artistsCache = now to list
        list
    }

    /**
     * Music Home's banner: the newest releases (else the recently added), each over its
     * artist's background (from the artist list, matched by name; one request, cached).
     */
    fun featured(): Flow<List<FeaturedAlbum>> = listFlow {
        val home = library.home()
        val releases = home.newReleases.orEmpty().ifEmpty { home.addedReleases.orEmpty() }
            .distinctBy { it.id }
            .take(FeaturedCount)
        val backgrounds = runCatching { loadArtists() }.getOrDefault(emptyList())
            .filter { it.backgroundUri.isNotEmpty() }
            .associate { it.name.lowercase() to it.backgroundUri }
        releases.map { r ->
            FeaturedAlbum(r.toAlbum(), backgrounds[r.artist.lowercase()].orEmpty())
        }
    }

    suspend fun artistDetails(artistId: String): ArtistDetails = coroutineScope {
        val id = numericId(artistId, ArtistPrefix)
        val popular = async { api.artistPopular(id).popular.orEmpty() }
        val view = api.artist(id)
        val releases = view.releases.orEmpty().sortedByDescending { it.date }
        ArtistDetails(
            artist = view.artist.toArtist().copy(
                imageUri = urls.absolute(view.image.ifEmpty { view.artist.image })
            ),
            backgroundUri = urls.absolute(view.background.ifEmpty { view.artist.background }),
            albums = releases.filter { it.isStudioAlbum() }.map { it.toAlbum() },
            singles = releases.filter { it.isSingleOrEp() }.map { it.toAlbum() },
            otherReleases = releases
                .filter { !it.isStudioAlbum() && !it.isSingleOrEp() }
                .map { it.toAlbum() },
            popular = popular.await().map { it.toTrack() },
            similar = view.similar.orEmpty().map { it.toArtist() },
            isFavorite = view.isFavorite,
        )
    }

    // ---- Albums ----

    suspend fun albumDetails(albumId: String): AlbumDetails {
        val view = api.release(numericId(albumId, AlbumPrefix))
        val album = view.release.toAlbum()
        return AlbumDetails(
            album = album.copy(coverUri = urls.absolute(view.image).ifEmpty { album.coverUri }),
            artistId = "$ArtistPrefix${view.artist.id}",
            backgroundUri = urls.absolute(view.background),
            tracks = view.tracks.orEmpty()
                .sortedWith(compareBy({ it.discNum }, { it.trackNum }))
                .map { it.toTrack() },
            discCount = view.release.discCount,
            similar = view.similar.orEmpty().map { it.toAlbum() },
        )
    }

    // ---- Favorites ----

    private val _favoriteTrackEtags = MutableStateFlow<Set<String>>(emptySet())

    /** ETags of the user's favorite tracks, as last loaded or changed here (for ♥ buttons). */
    val favoriteTrackEtags: StateFlow<Set<String>> = _favoriteTrackEtags.asStateFlow()

    /** Favorite artists (A–Z) and tracks (as the server orders them, which Play all follows). */
    suspend fun favorites(): MusicFavorites {
        val view = api.favorites()
        val tracks = view.tracks.orEmpty().map { it.toTrack() }
        _favoriteTrackEtags.value = tracks.map { it.etag }.filter { it.isNotEmpty() }.toSet()
        return MusicFavorites(
            artists = view.artists.orEmpty()
                .sortedByTitle { it.sortName.ifEmpty { it.name } }
                .map { it.toArtist() },
            tracks = tracks,
        )
    }

    /** Loads the favorite track ETags if they haven't been yet. */
    suspend fun loadFavoriteTracks() {
        if (_favoriteTrackEtags.value.isEmpty()) runCatching { favorites() }
    }

    suspend fun setArtistFavorite(arid: String, favorite: Boolean) =
        api.setFavorite("artists/$arid", favorite)

    /** Updates [favoriteTrackEtags] right away; reverted if the server refuses. */
    suspend fun setTrackFavorite(etag: String, favorite: Boolean) {
        val before = _favoriteTrackEtags.value
        _favoriteTrackEtags.value = if (favorite) before + etag else before - etag
        try {
            api.setFavorite("tracks/$etag", favorite)
        } catch (e: Exception) {
            _favoriteTrackEtags.value = before
            throw e
        }
    }

    // ---- Radio and playlists ----

    /** The user's stations by kind (live streams first, then genres and decades). */
    suspend fun radio(): List<StationGroup> {
        val view = api.radio()
        return listOf(
            StationKind.Stream to view.stream,
            StationKind.Genre to view.genre,
            StationKind.Period to view.period,
            StationKind.Artist to view.artist,
            StationKind.Similar to view.similar,
            StationKind.Series to view.series,
            StationKind.Other to view.other,
        ).mapNotNull { (kind, stations) ->
            stations.orEmpty().takeIf { it.isNotEmpty() }?.let { list ->
                StationGroup(kind, list.map { it.toStation() })
            }
        }
    }

    suspend fun playlists(): List<MusicPlaylist> =
        api.playlists().playlists.orEmpty()
            .sortedByTitle { it.name }
            .map { MusicPlaylist("$PlaylistPrefix${it.id}", it.name, it.trackCount) }

    // ---- Activity ----

    /** Play counts for [interval], from the user's Takeout activity. */
    suspend fun activity(interval: ActivityInterval): ActivityStats {
        val view = api.trackStats(interval.api)
        return ActivityStats(
            artists = view.artists.orEmpty().map { Counted(it.artist.toArtist(), it.count) },
            albums = view.releases.orEmpty().map { Counted(it.release.toAlbum(), it.count) },
            tracks = view.tracks.orEmpty().map { Counted(it.track.toTrack(), it.count) },
        )
    }

    /** The user's listening history on Takeout (every device), newest first. */
    suspend fun recentTracks(): List<Track> =
        api.recentTracks().tracks.orEmpty().map { it.track.toTrack() }

    /** [tracks] as a queue (those with a known stream), starting at [startIndex]. */
    fun tracksQueue(title: String, tracks: List<Track>, startIndex: Int = 0): MusicQueue {
        val playable = tracks.filter { it.location.isNotEmpty() }
        val start = tracks.getOrNull(startIndex)?.let { playable.indexOf(it) }?.coerceAtLeast(0) ?: 0
        return MusicQueue(
            title = title,
            creator = "",
            imageUri = "",
            items = playable.map {
                QueueItem(it.title, it.artist, it.album, it.coverUri, uri = it.location, etag = it.etag)
            },
            startIndex = start,
        )
    }

    // ---- Search ----

    /**
     * Artists, releases, tracks and stations matching [query], which is passed to the server
     * as typed so its query syntax works ("artist:name", "guitar:person", plain words).
     */
    suspend fun search(query: String): MusicSearchResults {
        if (query.isBlank()) return MusicSearchResults()
        val view = api.search(query.trim())
        return MusicSearchResults(
            artists = view.artists.orEmpty().map { it.toArtist() },
            albums = view.releases.orEmpty().map { it.toAlbum() },
            tracks = view.tracks.orEmpty().map { it.toTrack() },
            stations = view.stations.orEmpty().map { it.toStation() },
        )
    }

    // ---- Play queues ----

    /** What a server search ref (see [TakeoutApi.resolveRef]) plays, titled [title]. */
    suspend fun refQueue(ref: String, title: String): MusicQueue =
        api.resolveRef(ref, title).toQueue(urls::absolute)

    /** This track first; the server follows it with similar tracks (a radio from it). */
    suspend fun trackQueue(trackId: String): MusicQueue =
        api.playlist("/api/tracks/${numericId(trackId, TrackPrefix)}/playlist").toQueue(urls::absolute)

    /** The album's tracks, starting at [startIndex]. */
    suspend fun albumQueue(albumId: String, startIndex: Int = 0): MusicQueue =
        api.playlist("/api/releases/${numericId(albumId, AlbumPrefix)}/playlist")
            .toQueue(urls::absolute)
            .startingAt(startIndex)

    /** A station's tracks (a fresh mix each time) or its live stream. */
    suspend fun stationQueue(stationId: String, startIndex: Int = 0): MusicQueue =
        api.playlist("/api/radio/${numericId(stationId, StationPrefix)}/playlist")
            .toQueue(urls::absolute)
            .startingAt(startIndex)

    suspend fun playlistQueue(playlistId: String, startIndex: Int = 0): MusicQueue =
        api.playlist("/api/playlists/${numericId(playlistId, PlaylistPrefix)}/playlist")
            .toQueue(urls::absolute)
            .startingAt(startIndex)

    /** All favorite tracks. */
    suspend fun favoritesQueue(startIndex: Int = 0): MusicQueue =
        api.playlist("/api/favorite/tracks/playlist")
            .toQueue(urls::absolute)
            .startingAt(startIndex)

    /** One of the server's per-artist mixes, starting at [startIndex]. */
    suspend fun artistQueue(artistId: String, mix: ArtistMix, startIndex: Int = 0): MusicQueue =
        api.playlist("/api/artists/${numericId(artistId, ArtistPrefix)}/${mix.resource}/playlist")
            .toQueue(urls::absolute)
            .startingAt(startIndex)

    // ---- Mapping ----

    private fun ApiArtist.toArtist() = Artist(
        id = "$ArtistPrefix$id",
        name = name,
        imageUri = urls.absolute(image),
        genre = genre,
        arid = arid,
        backgroundUri = urls.absolute(background),
    )

    private fun ApiRelease.toAlbum() = Album(
        id = "$AlbumPrefix$id",
        name = name,
        artist = artist,
        year = year(date.ifEmpty { releaseDate }),
        coverUri = urls.releaseCover(reid, rgid, artwork, frontArtwork, otherArtwork, groupArtwork),
        type = type,
    )

    private fun ApiTrack.toTrack() = Track(
        id = "$TrackPrefix$id",
        title = title,
        artist = trackArtist.ifEmpty { artist },
        album = releaseTitle.ifEmpty { release },
        trackNum = trackNum,
        discNum = discNum,
        coverUri = urls.releaseCover(reid, rgid, artwork, frontArtwork, otherArtwork, groupArtwork),
        etag = etag,
        location = if (uuid.isEmpty()) "" else urls.absolute("/api/tracks/$uuid/location"),
    )

    private fun ApiStation.toStation() = Station(
        id = "$StationPrefix$id",
        name = name,
        description = description,
        imageUri = urls.absolute(image),
    )

    companion object {
        const val ArtistPrefix = "ar-"
        const val AlbumPrefix = "al-"
        const val TrackPrefix = "t-"
        const val StationPrefix = "st-"
        const val PlaylistPrefix = "pl-"
        private const val ArtistsMaxAgeMs = 5 * 60_000L
        private const val FeaturedCount = 8

        fun numericId(id: String, prefix: String): Int =
            id.removePrefix(prefix).toIntOrNull()
                ?: throw IllegalArgumentException("Not a $prefix id: $id")

        /** "1969-10-24T00:00:00Z" -> "1969"; MusicBrainz's "0001-…" (unknown) -> "". */
        fun year(date: String): String =
            date.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) && it != "0001" }
                .orEmpty()

        private fun ApiRelease.isStudioAlbum() =
            type.equals("Album", ignoreCase = true) && secondaryType.isEmpty()

        private fun ApiRelease.isSingleOrEp() =
            type.equals("Single", ignoreCase = true) || type.equals("EP", ignoreCase = true)

        /** The queue starting at [index], kept within its items. */
        fun MusicQueue.startingAt(index: Int): MusicQueue =
            copy(startIndex = index.coerceIn(0, items.lastIndex.coerceAtLeast(0)))

        /** A spiff playlist as a queue; entries without a location are dropped. */
        fun ApiPlaylist.toQueue(absolute: (String) -> String): MusicQueue {
            val items = playlist.tracks.orEmpty().mapNotNull { entry ->
                val location = entry.location?.firstOrNull()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                QueueItem(
                    title = entry.title,
                    artist = entry.creator,
                    album = entry.album,
                    imageUri = absolute(entry.image),
                    backgroundUri = absolute(entry.background),
                    uri = absolute(location),
                    etag = entry.identifier?.firstOrNull().orEmpty(),
                )
            }
            return MusicQueue(
                title = playlist.title,
                creator = playlist.creator,
                imageUri = absolute(playlist.image),
                items = items,
                location = playlist.location,
            ).startingAt(index)
        }
    }
}

/** The server's per-artist playlists (`/api/artists/{id}/{resource}/playlist`). */
enum class ArtistMix(val resource: String) {
    /** All of the artist's tracks, shuffled. */
    Shuffle("shuffle"),
    Popular("popular"),
    /** Tracks by the artist and similar artists. */
    Radio("radio"),
}
