package com.takeoutfm.shared.video

import com.takeoutfm.shared.model.ApiEpisode
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.video.TakeoutVideoRepository.Companion.dollars
import com.takeoutfm.shared.video.TakeoutVideoRepository.Companion.fraction
import com.takeoutfm.shared.video.TakeoutVideoRepository.Companion.nextInOrder
import com.takeoutfm.shared.video.TakeoutVideoRepository.Companion.playOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoRulesTest {
    private fun person(id: String, role: String = "") = CastCredit(id, "Name $id", role, "")

    @Test
    fun directorsComeFirstLabelledDirector() {
        val result = withDirectorsFirst(
            directors = listOf(person("9"), person("8")),
            cast = listOf(person("1", "Ben"), person("2", "Barbra")),
        )
        assertEquals(listOf("9", "8", "1", "2"), result.map { it.personId })
        assertEquals(listOf("Director", "Director", "Ben", "Barbra"), result.map { it.role })
    }

    @Test
    fun directorWhoActsGetsOneCard() {
        val result = withDirectorsFirst(
            directors = listOf(person("5")),
            cast = listOf(person("1", "Ben"), person("5", "Neil")),
        )
        assertEquals(listOf("5", "1"), result.map { it.personId })
        assertEquals("Director · as Neil", result.first().role)
    }

    @Test
    fun repeatedDirectorsAreMerged() {
        val result = withDirectorsFirst(listOf(person("5"), person("5")), emptyList())
        assertEquals(listOf("5"), result.map { it.personId })
    }

    private fun ep(id: Int, season: Int, episode: Int) =
        ApiEpisode(id = id, season = season, episode = episode, etag = "e$id")

    private fun watched(etag: String, offset: Int, date: String) =
        etag to ApiOffset(etag, offset, 3000, date)

    @Test
    fun specialsOnlyPlayWhenThereIsNothingElse() {
        val episodes = listOf(ep(3, 2, 1), ep(9, 0, 1), ep(1, 1, 1), ep(2, 1, 2))
        assertEquals(listOf(1, 2, 3), playOrder(episodes).map { it.id })
        assertEquals(listOf(9), playOrder(listOf(ep(9, 0, 1))).map { it.id })
    }

    @Test
    fun playContinuesFromTheLastWatchedEpisode() {
        val order = playOrder(listOf(ep(1, 1, 1), ep(2, 1, 2), ep(3, 1, 3)))
        assertEquals(1, nextInOrder(order, emptyMap())?.id)
        // Episode 2 half watched (most recent): resume it.
        val partway = mapOf(
            watched("e1", 2990, "2026-10-01T00:00:00Z"),
            watched("e2", 600, "2026-10-02T00:00:00Z"),
        )
        assertEquals(2, nextInOrder(order, partway)?.id)
        // Episode 2 finished: the next one; the last finished starts over.
        assertEquals(3, nextInOrder(order, mapOf(watched("e2", 2990, "2026-10-02T00:00:00Z")))?.id)
        assertEquals(1, nextInOrder(order, mapOf(watched("e3", 2990, "2026-10-02T00:00:00Z")))?.id)
    }

    @Test
    fun progressFallsBackToTheRuntime() {
        assertEquals(0.5f, fraction(ApiOffset("a", 1500, 3000, ""), 0))
        assertEquals(0.25f, fraction(ApiOffset("a", 900, 0, ""), 60))
        assertNull(fraction(ApiOffset("a", 900, 0, ""), 0))
    }

    @Test
    fun searchRequiresEveryWordAndDropsSyntax() {
        assertEquals("+evil +dead", allWordsQuery("evil dead"))
        assertEquals("+artist +Beck", allWordsQuery("artist:Beck"))
        assertEquals("", allWordsQuery("  +- "))
    }

    @Test
    fun unknownDollarsAreZero() {
        assertEquals(325_000L, dollars("325000"))
        assertEquals(0L, dollars(""))
        assertEquals(0L, dollars("n/a"))
        assertEquals(0L, dollars("0"))
    }
}
