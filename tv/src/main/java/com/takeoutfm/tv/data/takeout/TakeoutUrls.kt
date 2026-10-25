package com.takeoutfm.tv.data.takeout

import com.takeoutfm.shared.TakeoutPaths
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full URLs on the signed-in server for paths the API returns (stream locations, images).
 * Shared by the video and music repositories.
 */
@Singleton
class TakeoutUrls @Inject constructor(
    private val session: TakeoutSession,
) {
    /** [location] as a full URL; already-absolute URLs are kept, empty stays empty. */
    fun absolute(location: String): String = TakeoutPaths.absolute(session.endpoint, location)

    /** A TMDB image the server proxies, e.g. size "w342" and path "/abc.jpg"; "" without a path. */
    fun tmdbImage(size: String, path: String): String =
        TakeoutPaths.tmdbImage(session.endpoint, size, path)

    /**
     * A release's cover through the server's Cover Art Archive proxy, by the server's own rule;
     * "" when it has no artwork.
     */
    fun releaseCover(
        reid: String,
        rgid: String,
        artwork: Boolean,
        frontArtwork: Boolean,
        otherArtwork: String,
        groupArtwork: Boolean,
    ): String = absolute(
        TakeoutPaths.releaseCoverPath(reid, rgid, artwork, frontArtwork, otherArtwork, groupArtwork)
    )
}
