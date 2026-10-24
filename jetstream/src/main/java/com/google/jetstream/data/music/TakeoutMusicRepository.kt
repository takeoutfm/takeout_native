package com.google.jetstream.data.music

import com.google.jetstream.data.entities.music.Album
import com.google.jetstream.data.entities.music.AlbumDetails
import com.google.jetstream.data.entities.music.Artist
import com.google.jetstream.data.entities.music.ArtistDetails
import com.google.jetstream.data.entities.music.MusicQueue
import com.google.jetstream.data.entities.music.QueueItem
import com.google.jetstream.data.entities.music.Track
import com.google.jetstream.data.takeout.TakeoutApi
import com.google.jetstream.data.takeout.TakeoutLibrary
import com.google.jetstream.data.takeout.TakeoutUrls
import com.google.jetstream.data.util.listFlow
import com.google.jetstream.data.util.sortedByTitle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The server's music library for the music screens. Ids are "ar-<id>" (artists), "al-<id>"
 * (releases) and "t-<id>" (tracks); play requests return a [MusicQueue] from the server's spiff
 * playlists.
 */
@Singleton
class TakeoutMusicRepository @Inject constructor(
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
    fun artists(): Flow<List<Artist>> = listFlow {
        artistsLock.withLock {
            val cached = artistsCache
            if (cached != null && System.currentTimeMillis() - cached.first < ArtistsMaxAgeMs) {
                return@listFlow cached.second
            }
            val list = api.artists().artists.orEmpty()
                .sortedByTitle { it.sortName.ifEmpty { it.name } }
                .map { it.toArtist() }
            artistsCache = System.currentTimeMillis() to list
            list
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

    // ---- Play queues ----

    /** The album's tracks, starting at [startIndex]. */
    suspend fun albumQueue(albumId: String, startIndex: Int = 0): MusicQueue =
        api.playlist("/api/releases/${numericId(albumId, AlbumPrefix)}/playlist")
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
    )

    companion object {
        const val ArtistPrefix = "ar-"
        const val AlbumPrefix = "al-"
        const val TrackPrefix = "t-"
        private const val ArtistsMaxAgeMs = 5 * 60_000L

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
                    uri = absolute(location),
                    etag = entry.identifier?.firstOrNull().orEmpty(),
                )
            }
            return MusicQueue(
                title = playlist.title,
                creator = playlist.creator,
                imageUri = absolute(playlist.image),
                items = items,
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
