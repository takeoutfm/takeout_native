package com.takeoutfm.shared.progress

import com.takeoutfm.shared.model.ApiOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant

class ProgressRulesTest {
    private val now = Instant.parse("2026-10-18T20:00:00Z")

    private fun offset(etag: String, offset: Int, duration: Int, daysAgo: Long) =
        ApiOffset(etag, offset, duration, (now - daysAgo.days).toString())

    private val recent = offset("recent", 600, 5400, daysAgo = 1)
    private val lastWeek = offset("lastWeek", 600, 5400, daysAgo = 6)
    private val lastMonth = offset("lastMonth", 600, 5400, daysAgo = 20)
    private val longAgo = offset("longAgo", 600, 5400, daysAgo = 200)
    private val all = listOf(longAgo, lastMonth, recent, lastWeek)

    private fun select(days: Int) =
        ProgressRules.selectInProgress(all, days, now).map { it.etag }

    @Test
    fun windowDropsOlderOffsets() {
        assertEquals(listOf("recent", "lastWeek"), select(7))
        assertEquals(listOf("recent", "lastWeek", "lastMonth"), select(30))
    }

    @Test
    fun allTimeKeepsEverythingNewestFirst() {
        assertEquals(
            listOf("recent", "lastWeek", "lastMonth", "longAgo"),
            select(ProgressRules.AllTime)
        )
    }

    @Test
    fun finishedAndBarelyStartedAreNotInProgress() {
        val finished = offset("finished", 5350, 5400, daysAgo = 1)
        val barelyStarted = offset("barely", 5, 5400, daysAgo = 1)
        val result = ProgressRules.selectInProgress(
            listOf(finished, barelyStarted, recent), 30, now
        ).map { it.etag }
        assertEquals(listOf("recent"), result)
    }

    @Test
    fun finishedMeansWithinTwoMinutesOfTheEnd() {
        assertTrue(ProgressRules.isFinished(offset("a", 5300, 5400, 0)))
        assertFalse(ProgressRules.isFinished(offset("b", 5200, 5400, 0)))
        // Unknown duration is never "finished".
        assertFalse(ProgressRules.isFinished(offset("c", 5300, 0, 0)))
    }
}
