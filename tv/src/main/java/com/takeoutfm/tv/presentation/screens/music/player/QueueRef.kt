package com.takeoutfm.tv.presentation.screens.music.player

import com.takeoutfm.shared.music.ArtistMix

/**
 * What the music player should play, as a navigation argument: "album:al-12:3" (album al-12
 * from its 4th track), "artist:ar-5:shuffle:0" (an artist mix), "station:st-3:0",
 * "playlist:pl-2:0", "favorites:4" (favorite tracks from the 5th), "history:h123:0" (a
 * Recently Played entry, which resumes where it was left; the index is unused) or
 * "episodes:ps-4:2" (a podcast's episodes from the 3rd, resuming it if started).
 */
sealed class QueueRef {
    abstract val startIndex: Int

    data class Album(val albumId: String, override val startIndex: Int = 0) : QueueRef()

    data class Artist(
        val artistId: String,
        val mix: ArtistMix,
        override val startIndex: Int = 0,
    ) : QueueRef()

    data class Station(val stationId: String, override val startIndex: Int = 0) : QueueRef()

    data class Playlist(val playlistId: String, override val startIndex: Int = 0) : QueueRef()

    data class Favorites(override val startIndex: Int = 0) : QueueRef()

    data class History(val entryId: String, override val startIndex: Int = 0) : QueueRef()

    data class Episodes(val seriesId: String, override val startIndex: Int = 0) : QueueRef()

    fun encode(): String = when (this) {
        is Album -> "album:$albumId:$startIndex"
        is Artist -> "artist:$artistId:${mix.resource}:$startIndex"
        is Station -> "station:$stationId:$startIndex"
        is Playlist -> "playlist:$playlistId:$startIndex"
        is Favorites -> "favorites:$startIndex"
        is History -> "history:$entryId:$startIndex"
        is Episodes -> "episodes:$seriesId:$startIndex"
    }

    companion object {
        fun parse(value: String): QueueRef? {
            val parts = value.split(':')
            return when (parts.firstOrNull()) {
                "album" -> if (parts.size == 3) {
                    parts[2].toIntOrNull()?.let { Album(parts[1], it) }
                } else {
                    null
                }
                "station", "playlist", "history", "episodes" -> if (parts.size == 3) {
                    parts[2].toIntOrNull()?.let { start ->
                        when (parts[0]) {
                            "station" -> Station(parts[1], start)
                            "playlist" -> Playlist(parts[1], start)
                            "episodes" -> Episodes(parts[1], start)
                            else -> History(parts[1], start)
                        }
                    }
                } else {
                    null
                }
                "favorites" -> if (parts.size == 2) {
                    parts[1].toIntOrNull()?.let { Favorites(it) }
                } else {
                    null
                }
                "artist" -> if (parts.size == 4) {
                    val mix = ArtistMix.entries.firstOrNull { it.resource == parts[2] }
                    val start = parts[3].toIntOrNull()
                    if (mix != null && start != null) Artist(parts[1], mix, start) else null
                } else {
                    null
                }
                else -> null
            }
        }
    }
}
