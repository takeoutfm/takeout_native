package com.takeoutfm.shared.progress

import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.progress.ProgressRules.confirmed
import com.takeoutfm.shared.progress.ProgressRules.keepNewest
import com.takeoutfm.shared.progress.ProgressRules.newestByEtag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant

class ProgressSyncTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")
    private fun offset(etag: String, seconds: Int, minutesAgo: Long) =
        ApiOffset(etag, seconds, 3600, (now - minutesAgo.minutes).toString())

    @Test
    fun newerOffsetWinsWhetherLocalOrServer() {
        val server = listOf(offset("a", 100, 10), offset("b", 900, 1))
        val local = listOf(offset("a", 300, 2), offset("b", 50, 30))
        val newest = newestByEtag(server + local)
        assertEquals(300, newest.getValue("a").offset) // local is newer
        assertEquals(900, newest.getValue("b").offset) // another device's later progress
    }

    @Test
    fun onlyUnchangedOffsetsAreConfirmed() {
        val sent = listOf(offset("a", 300, 2), offset("b", 50, 5))
        // "a" was reported again while the upload was in flight.
        val local = mapOf("a" to offset("a", 330, 1), "b" to offset("b", 50, 5))
        assertEquals(listOf("b"), confirmed(sent, local))
    }

    @Test
    fun keepsNewestUpToTheCapAndEveryPendingOne() {
        val offsets = (0 until 5).map { offset("t$it", 60, it.toLong()) }
        val kept = keepNewest(offsets, pending = setOf("t4"), max = 2)
        assertEquals(listOf("t0", "t1", "t4"), kept.map { it.etag })
    }
}
