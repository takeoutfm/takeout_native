package com.google.jetstream.data.listen

import com.google.jetstream.data.listen.ListenReporter.Companion.countsAsListened
import org.junit.Assert.assertFalse
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
}
