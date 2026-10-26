package com.takeoutfm.shared.podcast

import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository.Companion.episodeProgress
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
}
