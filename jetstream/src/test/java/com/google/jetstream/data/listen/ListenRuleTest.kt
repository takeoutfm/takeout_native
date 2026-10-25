package com.google.jetstream.data.listen

import com.google.jetstream.data.listen.ListenReporter.Companion.countsAsListened
import com.google.jetstream.data.listen.ListenReporter.Companion.countsAsStreamListen
import com.google.jetstream.data.listen.ListenReporter.Companion.parseStreamTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenRuleTest {
    @Test
    fun halfTheTrackCounts() {
        assertFalse(countsAsListened(89_000, 180_000))
        assertTrue(countsAsListened(90_000, 180_000))
    }

    @Test
    fun fourMinutesCountsForLongTracks() {
        assertFalse(countsAsListened(239_000, 20 * 60_000))
        assertTrue(countsAsListened(240_000, 20 * 60_000))
    }

    @Test
    fun unknownLengthNeedsFourMinutes() {
        assertFalse(countsAsListened(200_000, 0))
        assertTrue(countsAsListened(240_000, 0))
    }

    @Test
    fun streamSongsHeardFromTheStartCount() {
        assertTrue(countsAsStreamListen(heardFromStart = true, heardMs = 31_000))
        assertFalse(countsAsStreamListen(heardFromStart = true, heardMs = 20_000))
    }

    @Test
    fun songPlayingWhenTunedInNeedsFourMinutes() {
        assertFalse(countsAsStreamListen(heardFromStart = false, heardMs = 200_000))
        assertTrue(countsAsStreamListen(heardFromStart = false, heardMs = 240_000))
    }

    @Test
    fun streamTitlesSplitIntoArtistAndTitle() {
        assertEquals("Holiday" to "Annabelle", parseStreamTitle("Holiday - Annabelle"))
        assertEquals(
            "Jay-Z" to "99 Problems - Live",
            parseStreamTitle("Jay-Z - 99 Problems - Live"),
        )
        assertNull(parseStreamTitle("SomaFM: Boot Liquor"))
        assertNull(parseStreamTitle(" - Untitled"))
    }
}
