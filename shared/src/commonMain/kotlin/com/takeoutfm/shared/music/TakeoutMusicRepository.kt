package com.takeoutfm.shared.music

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.model.ApiArtist
import com.takeoutfm.shared.model.ApiPlaylist
import com.takeoutfm.shared.model.ApiRelease
import com.takeoutfm.shared.model.ApiRadioStation
import com.takeoutfm.shared.model.ApiStation
import com.takeoutfm.shared.api.HttpException
import com.takeoutfm.shared.model.ApiTrack
import com.takeoutfm.shared.util.EditedState
import com.takeoutfm.shared.util.listFlow
import com.takeoutfm.shared.util.sortedByTitle
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

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

    /** The artist's releases the library doesn't have, newest first (the server's Want List). */
    suspend fun wantList(artistId: String): List<WantedRelease> {
        val view = api.wantList(numericId(artistId, ArtistPrefix))
        return view.releases.orEmpty().map { r -> r.toWanted(view.artist.name.ifEmpty { r.artist }) }
            .sortedByDescending { it.year }
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
        ).also { details ->
            val arid = details.artist.arid
            if (arid.isNotEmpty()) {
                _favoriteArtistIds.update { if (details.isFavorite) it + arid else it - arid }
            }
        }
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

    private val _favoriteArtistIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * MusicBrainz ids ([Artist.arid]) of the user's favorite artists, as last loaded ([favorites],
     * [artistDetails]) or changed here ([setArtistFavorite]): the artist page's ♥ and the
     * Favorites page follow it (decision 017).
     */
    val favoriteArtistIds: StateFlow<Set<String>> = _favoriteArtistIds.asStateFlow()

    /** Favorite artists (A–Z) and tracks (as the server orders them, which Play all follows). */
    suspend fun favorites(): MusicFavorites {
        val view = api.favorites()
        val tracks = view.tracks.orEmpty().map { it.toTrack() }
        _favoriteTrackEtags.value = tracks.map { it.etag }.filter { it.isNotEmpty() }.toSet()
        _favoriteArtistIds.value = view.artists.orEmpty().map { it.arid }.filter { it.isNotEmpty() }.toSet()
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

    /** Updates [favoriteArtistIds] right away; reverted if the server refuses. */
    suspend fun setArtistFavorite(arid: String, favorite: Boolean) {
        val before = _favoriteArtistIds.value
        _favoriteArtistIds.value = if (favorite) before + arid else before - arid
        try {
            api.setFavorite("artists/$arid", favorite)
        } catch (e: Exception) {
            _favoriteArtistIds.value = before
            throw e
        }
    }

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

    /** The stations by kind: live stations, then streams, each in the server's order. */
    suspend fun radio(): List<StationGroup> {
        val view = api.radio()
        return listOf(
            StationKind.Live to view.live,
            StationKind.Stream to view.stream,
        ).mapNotNull { (kind, stations) ->
            stations.orEmpty().takeIf { it.isNotEmpty() }?.let { list ->
                StationGroup(kind, list.map { it.toStation() })
            }
        }
    }

    /** What's on each live station now, from one schedule request. */
    suspend fun radioNow(): RadioNow {
        val received = Clock.System.now().toEpochMilliseconds()
        val view = api.radioSchedule()
        val serverNow = if (view.time > 0) view.time else received
        val skew = serverNow - received
        val on = view.stations.orEmpty()
            .mapNotNull { it.toSchedule(skew).onNow(serverNow) }
            .associateBy { it.stationId }
        val firstEnd = on.values.minOfOrNull { it.track.endMs }
        return RadioNow(on, if (firstEnd == null) 0 else firstEnd - skew)
    }

    /**
     * Live station [stationId]'s schedule from [start] (default now) for [window]; null when
     * it's a stream (or off the air), which the server doesn't schedule.
     */
    suspend fun stationSchedule(
        stationId: String,
        start: Instant? = null,
        window: Duration = LiveWindow,
    ): RadioSchedule? {
        val received = Clock.System.now()
        val view = try {
            api.radioSchedule(
                numericId(stationId, StationPrefix),
                start,
                (start ?: received).plus(window),
            )
        } catch (e: HttpException) {
            if (e.code == 404) return null
            throw e
        }
        val serverNow = if (view.time > 0) view.time else received.toEpochMilliseconds()
        return view.stations.orEmpty().firstOrNull()?.toSchedule(serverNow - received.toEpochMilliseconds())
    }

    /**
     * What tuning in to live station [stationId] plays: the track on now from where it is, then
     * the next ones for [window]; null when it's a stream or nothing is on.
     */
    suspend fun liveQueue(stationId: String, window: Duration = LiveWindow): MusicQueue? {
        val schedule = stationSchedule(stationId, window = window) ?: return null
        val now = Clock.System.now().toEpochMilliseconds() + schedule.skewMs
        return schedule.toQueue(now)
    }

    /**
     * The live station's tracks after [queued]'s last one, to add to the queue (empty when it
     * has none): the schedule from that track's end, so a queue behind live has no gap.
     */
    suspend fun liveMore(queued: List<QueueItem>): List<QueueItem> {
        val last = queued.lastOrNull { it.live != null }?.live ?: return emptyList()
        val schedule = stationSchedule(last.stationId, Instant.fromEpochMilliseconds(last.endMs))
            ?: return emptyList()
        return LiveRadio.after(queued, schedule.tracks.map(schedule::queueItem))
    }

    private val playlistsState = EditedState<List<MusicPlaylist>?>(null)

    /**
     * The user's playlists A–Z as last loaded ([playlists]), kept up to date by every edit made
     * through this repository: pages that show them observe this rather than reloading after an
     * edit, wherever it was made. Null until first loaded.
     */
    val currentPlaylists: StateFlow<List<MusicPlaylist>?> = playlistsState.flow

    /** Loads the user's playlists A–Z, and makes them [currentPlaylists] (unless an edit overlapped). */
    suspend fun playlists(): List<MusicPlaylist> = playlistsState.load(
        fetch = {
            api.playlists().playlists.orEmpty()
                .map { MusicPlaylist("$PlaylistPrefix${it.id}", it.name, it.trackCount) }
                .sortedPlaylists()
        },
        toState = { it },
    )

    private fun List<MusicPlaylist>.sortedPlaylists() = sortedByTitle { it.name }

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

    // ---- Playlist editing ----

    // Each edit updates [currentPlaylists] once the server has made it.

    suspend fun createPlaylist(name: String): MusicPlaylist = playlistsState.edit {
        val created = api.createPlaylist(name.trim()).let { MusicPlaylist("$PlaylistPrefix${it.id}", it.name, it.trackCount) }
        playlistsState.update { list -> list?.let { (it + created).sortedPlaylists() } }
        created
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) = playlistsState.edit {
        api.addToPlaylist(numericId(playlistId, PlaylistPrefix), "/music/tracks/${numericId(trackId, TrackPrefix)}")
        changeTrackCount(playlistId) { it + 1 }
    }

    /** The album's tracks: how many only the server knows, so the playlists load again. */
    suspend fun addAlbumToPlaylist(playlistId: String, albumId: String) = playlistsState.edit {
        api.addToPlaylist(numericId(playlistId, PlaylistPrefix), "/music/releases/${numericId(albumId, AlbumPrefix)}/tracks")
        if (playlistsState.value != null) runCatching { playlists() }
        Unit
    }

    suspend fun removeFromPlaylist(playlistId: String, index: Int) = playlistsState.edit {
        api.removeFromPlaylist(numericId(playlistId, PlaylistPrefix), index)
        changeTrackCount(playlistId) { maxOf(0, it - 1) }
    }

    suspend fun deletePlaylist(playlistId: String) = playlistsState.edit {
        api.deletePlaylist(numericId(playlistId, PlaylistPrefix))
        playlistsState.update { list -> list?.filter { it.id != playlistId } }
    }

    private fun changeTrackCount(playlistId: String, change: (Int) -> Int) =
        playlistsState.update { list ->
            list?.map { if (it.id == playlistId) it.copy(trackCount = change(it.trackCount)) else it }
        }

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

    /**
     * A station: a live one tuned in ([liveQueue], the next [window] of it), else its stream.
     * A live station with nothing on plays its playlist, the schedule as plain tracks.
     */
    suspend fun stationQueue(stationId: String, startIndex: Int = 0, window: Duration = LiveWindow): MusicQueue =
        liveQueue(stationId, window)
            ?: api.playlist("/api/radio/${numericId(stationId, StationPrefix)}/playlist")
                .toQueue(urls::absolute)
                .startingAt(startIndex)

    /** What replaying Recently Played [entry] plays: a live station tunes in again. */
    suspend fun replayQueue(entry: PlayedQueue): MusicQueue =
        entry.liveStationId?.let { liveQueue(it) } ?: entry.toQueue()

    suspend fun playlistQueue(playlistId: String, startIndex: Int = 0): MusicQueue =
        api.playlist("/api/playlists/${numericId(playlistId, PlaylistPrefix)}/playlist")
            .toQueue(urls::absolute)
            .startingAt(startIndex)

    // ---- Playlist covers ----

    private val coversLock = Mutex()
    private val keptCovers = mutableMapOf<String, Pair<Int, List<String>>>()

    /**
     * Up to four different album covers from playlist [playlistId], for its mosaic (four in a
     * square, else the first alone). Kept while the app runs and fetched again when its
     * [trackCount] changes, so a grid of playlists doesn't load each one on every visit.
     */
    suspend fun playlistCovers(playlistId: String, trackCount: Int): List<String> {
        if (trackCount <= 0) return emptyList()
        coversLock.withLock {
            keptCovers[playlistId]?.takeIf { it.first == trackCount }?.let { return it.second }
        }
        return keepCovers(playlistId, playlistQueue(playlistId))
    }

    /** Keeps the covers of [queue], playlist [playlistId] as its page loaded it, and returns them. */
    suspend fun keepCovers(playlistId: String, queue: MusicQueue): List<String> {
        val covers = coversOf(queue)
        coversLock.withLock { keptCovers[playlistId] = queue.items.size to covers }
        return covers
    }

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
        sortName = sortName,
    )

    private fun ApiRelease.toAlbum() = Album(
        id = "$AlbumPrefix$id",
        name = name,
        artist = artist,
        year = year(date.ifEmpty { releaseDate }),
        coverUri = urls.releaseCover(reid, rgid, artwork, frontArtwork, otherArtwork, groupArtwork),
        type = type,
    )

    private fun ApiRelease.toWanted(artistName: String) = WantedRelease(
        name = if (disambiguation.isEmpty()) name else "$name ($disambiguation)",
        artist = artistName,
        year = year(date.ifEmpty { releaseDate }),
        type = type,
        country = country,
        coverUri = urls.releaseCover(reid, rgid, artwork, frontArtwork, otherArtwork, groupArtwork),
        amazonUri = if (asin.isEmpty()) "" else "https://www.amazon.com/dp/$asin",
        camelUri = if (asin.isEmpty()) "" else "https://camelcamelcamel.com/product/$asin",
        musicBrainzUri = if (rgid.isEmpty()) "" else "https://musicbrainz.org/release-group/$rgid",
        searchUri = "https://www.google.com/search?q=" + "$name by $artistName".encodeURLParameter(),
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
        live = type == "live",
    )

    private fun ApiRadioStation.toSchedule(skewMs: Long) = RadioSchedule(
        stationId = "$StationPrefix$id",
        name = name,
        imageUri = urls.absolute(image),
        tracks = tracks.orEmpty().filter { it.location.isNotEmpty() }.map {
            RadioTrack(
                startMs = it.start.toEpochMilliseconds(),
                endMs = it.end.toEpochMilliseconds(),
                title = it.title,
                artist = it.artist,
                album = it.release,
                imageUri = urls.absolute(it.image),
                uri = urls.absolute(it.location),
                etag = it.etag,
                trackId = "$TrackPrefix${it.id}",
                show = it.show,
            )
        },
        shows = shows.orEmpty().map { RadioShow(it.name, urls.absolute(it.image), it.description) },
        skewMs = skewMs,
    )

    companion object {
        /** The first four different covers in [queue] (fewer when it has fewer). */
        fun coversOf(queue: MusicQueue): List<String> =
            queue.items.map { it.imageUri }.filter { it.isNotEmpty() }.distinct().take(4)

        const val ArtistPrefix = "ar-"
        const val AlbumPrefix = "al-"
        const val TrackPrefix = "t-"
        const val StationPrefix = "st-"
        const val PlaylistPrefix = "pl-"
        private const val ArtistsMaxAgeMs = 5 * 60_000L

        /** How much of a live station is queued at a time (RADIO.md: about 6–8 tracks). */
        val LiveWindow: Duration = 30.minutes
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
@Serializable
enum class ArtistMix(val resource: String) {
    /** All of the artist's tracks, shuffled. */
    Shuffle("shuffle"),
    Popular("popular"),
    /** Tracks by the artist and similar artists. */
    Radio("radio"),
}
