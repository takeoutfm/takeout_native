package com.takeoutfm.shared.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleTracksTest {
    @Test
    fun languagesMatchHoweverTheFileWritesThem() {
        val tracks = listOf(MpvSubtitleTrack(1, "spa"), MpvSubtitleTrack(2, "eng"))
        assertEquals(2, subtitleTrack(tracks, "en"))
        assertEquals(1, subtitleTrack(listOf(MpvSubtitleTrack(1, "es"), MpvSubtitleTrack(2, "en")), "spa"))
    }

    @Test
    fun forcedTracksAreSkippedUnlessTheyAreAllThereIs() {
        val tracks = listOf(
            MpvSubtitleTrack(3, "eng", forced = true),
            MpvSubtitleTrack(4, "eng"),
            MpvSubtitleTrack(5, "fre", forced = true),
        )
        assertEquals(4, subtitleTrack(tracks, "eng"))
        assertEquals(5, subtitleTrack(tracks, "fr"))
    }

    @Test
    fun noneInTheLanguageLeavesTheTitlesOwn() {
        assertNull(subtitleTrack(listOf(MpvSubtitleTrack(1, "eng"), MpvSubtitleTrack(2, null)), "de"))
        assertNull(subtitleTrack(listOf(MpvSubtitleTrack(1, "eng")), ""))
    }
}
