package com.takeoutfm.shared.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleTrackLabelTest {
    @Test
    fun formatsFromFfmpegAndVlcNames() {
        assertEquals("SRT", subtitleFormat("subrip"))
        assertEquals("PGS", subtitleFormat("hdmv_pgs_subtitle"))
        assertEquals("PGS", subtitleFormat("Blu-ray Presentation Graphic Subtitles"))
        assertEquals("VobSub", subtitleFormat("DVD Subtitles"))
        assertEquals("ASS", subtitleFormat("ass"))
        assertEquals("Timed Text", subtitleFormat("MPEG-4 Timed Text"))
        assertEquals("Text", subtitleFormat("Text subtitles with various tags"))
        assertNull(subtitleFormat("something else"))
    }

    @Test
    fun labelsByLanguageNameFlagsAndFormat() {
        assertEquals("English · SDH · PGS", subtitleTrackLabel("English", "", sdh = true, format = "PGS", number = 1))
        assertEquals("English · Forced · SRT", subtitleTrackLabel("English", "", forced = true, format = "SRT", number = 1))
        assertEquals("Spanish · Latin America · SRT", subtitleTrackLabel("Spanish", "Latin America", format = "SRT", number = 2))
        assertEquals("Track 3", subtitleTrackLabel(null, "", format = null, number = 3))
    }

    @Test
    fun saysNothingTheNameAlreadySays() {
        assertEquals("English SDH · PGS", subtitleTrackLabel("English", "English SDH", sdh = true, format = "PGS", number = 1))
        assertEquals("French · Forced (SRT)", subtitleTrackLabel("French", "Forced (SRT)", forced = true, format = "SRT", number = 1))
    }

    @Test
    fun vlcsMadeUpNamesAreRecognised() {
        assertTrue(isGeneratedTrackName("Track 1 - [English]"))
        assertTrue(isGeneratedTrackName("Track 12"))
        assertFalse(isGeneratedTrackName("English SDH"))
        assertFalse(isGeneratedTrackName("Track 1 - Commentary"))
    }
}
