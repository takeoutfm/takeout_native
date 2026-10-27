package com.takeoutfm.shared.podcast

import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository.Companion.episodeProgress
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository.Companion.playedMark
import kotlin.test.Test
import kotlin.test.assertEquals

class PodcastProgressTest {
    private fun at(offset: Int, duration: Int) = ApiOffset("eid", offset, duration, "2026-10-25T00:00:00Z")

    @Test
    fun episodesAreNotStartedStartedOrComplete() {
        assertEquals(EpisodeProgress.NotStarted, episodeProgress(null))
        assertEquals(EpisodeProgress.NotStarted, episodeProgress(at(5, 3600)))
        assertEquals(EpisodeProgress.Started(0.5f, 30), episodeProgress(at(1800, 3600)))
        assertEquals(EpisodeProgress.Complete, episodeProgress(at(3550, 3600)))
    }

    @Test
    fun markingPlayedUsesTheKnownLengthOrATokenOne() {
        // Played before: its real length, at the end or back to the start.
        assertEquals(3600 to 3600, playedMark(at(1800, 3600), played = true))
        assertEquals(0 to 3600, playedMark(at(1800, 3600), played = false))
        // Never played: a token length that still reads as Complete / Not started.
        val (offset, duration) = playedMark(null, played = true)
        assertEquals(EpisodeProgress.Complete, episodeProgress(at(offset, duration)))
        val (start, length) = playedMark(null, played = false)
        assertEquals(EpisodeProgress.NotStarted, episodeProgress(at(start, length)))
    }
}
