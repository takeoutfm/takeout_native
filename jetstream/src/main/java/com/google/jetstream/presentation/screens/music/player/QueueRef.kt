package com.google.jetstream.presentation.screens.music.player

import com.google.jetstream.data.music.ArtistMix

/**
 * What the music player should play, as a navigation argument: "album:al-12:3" (album al-12
 * from its 4th track) or "artist:ar-5:shuffle:0" (an artist mix from its first track).
 */
sealed class QueueRef {
    abstract val startIndex: Int

    data class Album(val albumId: String, override val startIndex: Int = 0) : QueueRef()

    data class Artist(
        val artistId: String,
        val mix: ArtistMix,
        override val startIndex: Int = 0,
    ) : QueueRef()

    fun encode(): String = when (this) {
        is Album -> "album:$albumId:$startIndex"
        is Artist -> "artist:$artistId:${mix.resource}:$startIndex"
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
