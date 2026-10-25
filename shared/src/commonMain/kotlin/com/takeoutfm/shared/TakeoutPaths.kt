package com.takeoutfm.shared

/** URL rules for paths the Takeout API returns (stream locations, images, release covers). */
object TakeoutPaths {
    /** [location] on [endpoint]; already-absolute URLs are kept, empty stays empty. */
    fun absolute(endpoint: String, location: String): String = when {
        location.isEmpty() -> ""
        location.startsWith("http://") || location.startsWith("https://") -> location
        else -> endpoint + location
    }

    /** A TMDB image the server proxies, e.g. size "w342" and path "/abc.jpg"; "" without a path. */
    fun tmdbImage(endpoint: String, size: String, path: String): String =
        if (path.isEmpty()) "" else "$endpoint/img/tm/$size$path"

    /**
     * A release's cover through the server's Cover Art Archive proxy, by the server's own rule;
     * "" when it has no artwork.
     */
    fun releaseCoverPath(
        reid: String,
        rgid: String,
        artwork: Boolean,
        frontArtwork: Boolean,
        otherArtwork: String,
        groupArtwork: Boolean,
    ): String {
        val base = if (groupArtwork) "/img/mb/rg/$rgid" else "/img/mb/re/$reid"
        return when {
            artwork && frontArtwork -> "$base/front"
            artwork && otherArtwork.isNotEmpty() -> base
            else -> ""
        }
    }
}
