package com.takeoutfm.shared.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CachePolicyTest {
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    private fun entry(
        etag: String,
        bytes: Long,
        lastUsedMs: Long = 0,
        cached: Boolean = true,
        partial: Boolean = false,
        finishedPodcast: Boolean = false,
    ) = CacheEntry(etag, bytes, lastUsedMs, cached, partial, finishedPodcast)

    @Test
    fun autoIsATenthOfTheVolumeAtMostTenGb() {
        assertEquals(6_400 * mb, CachePolicy.limitBytes(CacheLimit.Auto, 64_000 * mb))
        assertEquals(10 * gb, CachePolicy.limitBytes(CacheLimit.Auto, 2_000 * gb))
        assertEquals(3 * gb, CachePolicy.limitBytes(CacheLimit.Fixed(3 * gb), 2_000 * gb))
    }

    @Test
    fun theFreeFloorIsTwoGbOrATenthWhicheverIsSmaller() {
        assertEquals(2 * gb, CachePolicy.minFreeBytes(256 * gb))
        assertEquals(1_600 * mb, CachePolicy.minFreeBytes(16_000 * mb))
    }

    @Test
    fun underBudgetNothingGoes() {
        val plan = CachePolicy.plan(listOf(entry("a", 1 * gb), entry("b", 2 * gb)), CacheLimit.Auto, 256 * gb, 100 * gb)
        assertEquals(emptyList(), plan.remove)
        assertEquals(3 * gb, plan.cachedBytes)
        assertFalse(plan.lowSpace)
    }

    @Test
    fun overBudgetTheLeastRecentlyUsedGoFirstDownToTheSlack() {
        // 10 GB budget (Auto on 256 GB), 12 GB cached: down to 9 GB
        val entries = (1..12).map { entry("t$it", 1 * gb, lastUsedMs = it.toLong()) }
        val plan = CachePolicy.plan(entries.shuffled(), CacheLimit.Auto, 256 * gb, 100 * gb)
        assertEquals(listOf("t1", "t2", "t3"), plan.remove)
        assertEquals(9 * gb, plan.cachedBytes)
    }

    @Test
    fun partialTracksThenFinishedEpisodesGoBeforeLeastRecentlyUsed() {
        val entries = listOf(
            entry("old", 1 * gb, lastUsedMs = 1),
            entry("episode", 1 * gb, lastUsedMs = 50, finishedPodcast = true),
            entry("partial", 1 * gb, lastUsedMs = 99, partial = true),
            entry("new", 1 * gb, lastUsedMs = 100),
        )
        val plan = CachePolicy.plan(entries, CacheLimit.Fixed(2 * gb), 256 * gb, 100 * gb)
        assertEquals(listOf("partial", "episode", "old"), plan.remove)
    }

    @Test
    fun downloadsAreNeverPurged() {
        val entries = listOf(
            entry("download", 50 * gb, lastUsedMs = 0, cached = false),
            entry("cached", 1 * gb, lastUsedMs = 100),
        )
        val plan = CachePolicy.plan(entries, CacheLimit.Fixed(512 * mb), 256 * gb, 100 * gb)
        assertEquals(listOf("cached"), plan.remove)
        assertEquals(50 * gb, plan.downloadedBytes)
    }

    @Test
    fun lowFreeSpaceShrinksTheBudget() {
        // 3 GB cached, 1 GB free on 64 GB: the floor is 2 GB, so the cache may hold 2 GB
        val entries = (1..3).map { entry("t$it", 1 * gb, lastUsedMs = it.toLong()) }
        val plan = CachePolicy.plan(entries, CacheLimit.Auto, 64 * gb, 1 * gb)
        assertEquals(2 * gb, plan.budgetBytes)
        assertEquals(listOf("t1", "t2"), plan.remove)
        assertFalse(plan.lowSpace)
    }

    @Test
    fun warnsWhenEvenAnEmptyCacheLeavesTooLittleFree() {
        // downloads fill the disk: 512 MB free, 1 GB cached, 2 GB floor
        val entries = listOf(entry("cached", 1 * gb), entry("download", 40 * gb, cached = false))
        val plan = CachePolicy.plan(entries, CacheLimit.Auto, 64 * gb, 512 * mb)
        assertEquals(listOf("cached"), plan.remove)
        assertEquals(0, plan.budgetBytes)
        assertTrue(plan.lowSpace)
    }
}
