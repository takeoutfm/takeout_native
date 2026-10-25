package com.takeoutfm.tv.presentation.screens.music.player

import com.takeoutfm.tv.data.music.ArtistMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueRefTest {
    @Test
    fun roundTrips() {
        val refs = listOf(
            QueueRef.Album("al-12", 3),
            QueueRef.Artist("ar-5", ArtistMix.Shuffle),
            QueueRef.Artist("ar-5", ArtistMix.Popular, 7),
            QueueRef.Station("st-3"),
            QueueRef.Playlist("pl-2", 4),
            QueueRef.Favorites(5),
            QueueRef.History("h1700000000000"),
        )
        refs.forEach { assertEquals(it, QueueRef.parse(it.encode())) }
        assertEquals("album:al-12:3", refs[0].encode())
    }

    @Test
    fun rejectsJunk() {
        assertNull(QueueRef.parse(""))
        assertNull(QueueRef.parse("album:al-1"))
        assertNull(QueueRef.parse("artist:ar-1:nope:0"))
        assertNull(QueueRef.parse("radio:1"))
    }
}
