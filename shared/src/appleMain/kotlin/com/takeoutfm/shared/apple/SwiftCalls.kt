package com.takeoutfm.shared.apple

import com.takeoutfm.shared.listen.ListenSession
import com.takeoutfm.shared.music.Album
import com.takeoutfm.shared.music.AlbumDetails
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.ArtistDetails
import com.takeoutfm.shared.music.ArtistMix
import com.takeoutfm.shared.music.MusicFavorites
import com.takeoutfm.shared.music.MusicPlaylist
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.MusicSearchResults
import com.takeoutfm.shared.music.PlayedQueue
import com.takeoutfm.shared.music.StationGroup
import com.takeoutfm.shared.music.Track
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.video.PersonInfo
import com.takeoutfm.shared.video.VideoCard
import com.takeoutfm.shared.video.VideoDetails
import com.takeoutfm.shared.video.VideoSearchResults
import kotlinx.coroutines.flow.first

/**
 * The shared calls the Swift app makes. Swift sees suspend functions as `async throws`, but a
 * Kotlin throwable not covered by @Throws crashes the app instead of throwing, so every call
 * that can fail (network, missing title) goes through here, declaring Throwable: an Error
 * isn't an Exception.
 */
class SwiftCalls(private val core: TakeoutCore) {
    @Throws(Throwable::class)
    suspend fun refreshLibrary() = core.library.refresh(maxAgeMs = 60_000)

    @Throws(Throwable::class)
    suspend fun recentlyAdded(): List<Album> = core.music.recentlyAdded().first()

    @Throws(Throwable::class)
    suspend fun newReleases(): List<Album> = core.music.newReleases().first()

    @Throws(Throwable::class)
    suspend fun albumDetails(albumId: String): AlbumDetails = core.music.albumDetails(albumId)

    @Throws(Throwable::class)
    suspend fun albumQueue(albumId: String, startIndex: Int): MusicQueue =
        core.music.albumQueue(albumId, startIndex)

    /** Every artist, A–Z. */
    @Throws(Throwable::class)
    suspend fun artists(): List<Artist> = core.music.artists().first()

    @Throws(Throwable::class)
    suspend fun artistDetails(artistId: String): ArtistDetails = core.music.artistDetails(artistId)

    @Throws(Throwable::class)
    suspend fun artistQueue(artistId: String, mix: ArtistMix): MusicQueue =
        core.music.artistQueue(artistId, mix)

    @Throws(Throwable::class)
    suspend fun setArtistFavorite(arid: String, favorite: Boolean) =
        core.music.setArtistFavorite(arid, favorite)

    /** [query] in the server's search syntax ("artist:Beck", plain words). */
    @Throws(Throwable::class)
    suspend fun search(query: String): MusicSearchResults = core.music.search(query)

    /** The track, followed by similar tracks. */
    @Throws(Throwable::class)
    suspend fun trackQueue(trackId: String): MusicQueue = core.music.trackQueue(trackId)

    /** [tracks] as they're listed, starting at [startIndex]. */
    fun tracksQueue(title: String, tracks: List<Track>, startIndex: Int): MusicQueue =
        core.music.tracksQueue(title, tracks, startIndex)

    @Throws(Throwable::class)
    suspend fun radio(): List<StationGroup> = core.music.radio()

    @Throws(Throwable::class)
    suspend fun stationQueue(stationId: String): MusicQueue = core.music.stationQueue(stationId)

    @Throws(Throwable::class)
    suspend fun playlists(): List<MusicPlaylist> = core.music.playlists()

    @Throws(Throwable::class)
    suspend fun playlistQueue(playlistId: String): MusicQueue = core.music.playlistQueue(playlistId)

    @Throws(Throwable::class)
    suspend fun favorites(): MusicFavorites = core.music.favorites()

    @Throws(Throwable::class)
    suspend fun favoritesQueue(startIndex: Int): MusicQueue = core.music.favoritesQueue(startIndex)

    /** Loads the favorite track ETags (core.music.favoriteTrackEtags) if they haven't been. */
    @Throws(Throwable::class)
    suspend fun loadFavoriteTracks() = core.music.loadFavoriteTracks()

    @Throws(Throwable::class)
    suspend fun setTrackFavorite(etag: String, favorite: Boolean) =
        core.music.setTrackFavorite(etag, favorite)

    // ---- Video ----

    /** Started-but-unfinished movies and episodes, most recently watched first. */
    @Throws(Throwable::class)
    suspend fun continueWatching(): List<VideoCard> = core.video.continueWatching(ProgressRules.AllTime)

    @Throws(Throwable::class)
    suspend fun videoRecentlyAdded(): List<VideoCard> = core.video.recentlyAdded()

    @Throws(Throwable::class)
    suspend fun videoNewReleases(): List<VideoCard> = core.video.newReleases()

    @Throws(Throwable::class)
    suspend fun recentShows(): List<VideoCard> = core.video.recentShows()

    /** Every movie, A–Z. */
    @Throws(Throwable::class)
    suspend fun movies(): List<VideoCard> = core.video.movies()

    /** Every TV series, A–Z. */
    @Throws(Throwable::class)
    suspend fun shows(): List<VideoCard> = core.video.shows()

    /** A movie ("m-"), series ("s-") or episode ("e-"). */
    @Throws(Throwable::class)
    suspend fun videoDetails(id: String): VideoDetails = core.video.details(id)

    @Throws(Throwable::class)
    suspend fun person(personId: String): PersonInfo = core.video.person(personId)

    @Throws(Throwable::class)
    suspend fun videoSearch(query: String): VideoSearchResults = core.video.search(query)

    @Throws(Throwable::class)
    suspend fun setVideoFavorite(path: String, favorite: Boolean) = core.video.setFavorite(path, favorite)

    /** Where to resume the video with [etag], in ms; 0 to start from the beginning. */
    @Throws(Throwable::class)
    suspend fun resumePositionMs(etag: String): Long = core.progress.resumePositionMs(etag)

    /** Saves watch progress (kept on the device until the server has it). */
    fun reportProgress(etag: String, positionMs: Long, durationMs: Long) =
        core.progress.report(etag, positionMs, durationMs)

    /** Starts [queue] in Recently Played; returns its entry id. */
    fun recordPlayed(queue: MusicQueue): String = core.history.record(queue)

    /** Where Recently Played entry [id] got to, so replaying it resumes there. */
    fun playedProgress(id: String, index: Int, positionMs: Long) =
        core.history.progress(id, index, positionMs)

    /** Recently Played, newest first. */
    fun recentlyPlayed(): List<PlayedQueue> = core.history.entries.value

    fun clearRecentlyPlayed() = core.history.clear()

    /** Decides listens for the player, reporting them per the Music settings. */
    fun listenSession(): ListenSession = ListenSession(core.listenReporter)

    /** Resends listens kept from earlier failures (offline, server down). */
    fun retryListens() = core.listenReporter.retry()

    /** Track Activity: report plays to the Takeout server. */
    var reportPlays: Boolean
        get() = core.settings.reportPlays.value
        set(value) = core.settings.setReportPlays(value)

    /** "Bearer <media token>" for a stream on the signed-in server, else null. */
    fun mediaAuthorization(url: String): String? = core.session.mediaAuthorization(url)

    val isSignedIn: Boolean get() = core.session.state.value.isLoggedIn
}
