package com.google.jetstream.data.takeout

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
    fun absolute(location: String): String = absolute(session.endpoint, location)

    /** A TMDB image the server proxies, e.g. size "w342" and path "/abc.jpg"; "" without a path. */
    fun tmdbImage(size: String, path: String): String = tmdbImage(session.endpoint, size, path)

    companion object {
        fun absolute(endpoint: String, location: String): String = when {
            location.isEmpty() -> ""
            location.startsWith("http://") || location.startsWith("https://") -> location
            else -> endpoint + location
        }

        fun tmdbImage(endpoint: String, size: String, path: String): String =
            if (path.isEmpty()) "" else "$endpoint/img/tm/$size$path"
    }
}
