package com.google.jetstream.data.takeout

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoutProgressTest {
    private val now = Instant.parse("2026-10-18T20:00:00Z")

    private fun offset(etag: String, offset: Int, duration: Int, daysAgo: Long) =
        ApiOffset(etag, offset, duration, now.minus(Duration.ofDays(daysAgo)).toString())

    private val recent = offset("recent", 600, 5400, daysAgo = 1)
    private val lastWeek = offset("lastWeek", 600, 5400, daysAgo = 6)
    private val lastMonth = offset("lastMonth", 600, 5400, daysAgo = 20)
    private val longAgo = offset("longAgo", 600, 5400, daysAgo = 200)
    private val all = listOf(longAgo, lastMonth, recent, lastWeek)

    private fun select(days: Int) =
        TakeoutProgress.selectInProgress(all, days, now).map { it.etag }

    @Test
    fun windowDropsOlderOffsets() {
        assertEquals(listOf("recent", "lastWeek"), select(7))
        assertEquals(listOf("recent", "lastWeek", "lastMonth"), select(30))
    }

    @Test
    fun allTimeKeepsEverythingNewestFirst() {
        assertEquals(
            listOf("recent", "lastWeek", "lastMonth", "longAgo"),
            select(TakeoutSettings.AllTime)
        )
    }

    @Test
    fun finishedAndBarelyStartedAreNotInProgress() {
        val finished = offset("finished", 5350, 5400, daysAgo = 1)
        val barelyStarted = offset("barely", 5, 5400, daysAgo = 1)
        val result = TakeoutProgress.selectInProgress(
            listOf(finished, barelyStarted, recent), 30, now
        ).map { it.etag }
        assertEquals(listOf("recent"), result)
    }

    @Test
    fun finishedMeansWithinTwoMinutesOfTheEnd() {
        assertTrue(TakeoutProgress.isFinished(offset("a", 5300, 5400, 0)))
        assertFalse(TakeoutProgress.isFinished(offset("b", 5200, 5400, 0)))
        // Unknown duration is never "finished".
        assertFalse(TakeoutProgress.isFinished(offset("c", 5300, 0, 0)))
    }
}
