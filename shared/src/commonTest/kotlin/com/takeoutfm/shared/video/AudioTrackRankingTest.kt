package com.takeoutfm.shared.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioTrackRankingTest {
    private fun track(
        language: String? = "en",
        codec: AudioCodec = AudioCodec.Common,
        channels: Int = 2,
        bitrate: Int = 0,
        isDefault: Boolean = false,
        label: String? = null,
        isCommentary: Boolean = false,
    ) = AudioCandidate(language, label, codec, channels, bitrate, isDefault, isCommentary)

    @Test
    fun moreChannelsBeatsTheDefaultFlag() {
        val tracks = listOf(
            track(channels = 2, isDefault = true),
            track(codec = AudioCodec.AC3, channels = 6),
        )
        assertEquals(1, bestAudioTrack(tracks, "en"))
    }

    @Test
    fun preferredLanguageBeatsChannels() {
        val tracks = listOf(
            track(language = "fr", channels = 6),
            track(language = "en", channels = 2),
        )
        assertEquals(1, bestAudioTrack(tracks, "en"))
    }

    @Test
    fun languageCodesOfDifferentFormsMatch() {
        val tracks = listOf(
            track(language = "fra", channels = 6),
            track(language = "eng", channels = 2),
        )
        assertEquals(1, bestAudioTrack(tracks, "en-US"))
        assertEquals("de", normalizeLanguage("ger"))
        assertEquals("de", normalizeLanguage("deu"))
        assertEquals("pt", normalizeLanguage("pt_BR"))
        assertEquals("tlh", normalizeLanguage("tlh"))
        assertNull(normalizeLanguage("und"))
        assertNull(normalizeLanguage(" "))
    }

    @Test
    fun withNoLanguageMatchTheDefaultTrackWins() {
        val tracks = listOf(
            track(language = "ja", channels = 6),
            track(language = "ja", channels = 2, isDefault = true),
        )
        assertEquals(1, bestAudioTrack(tracks, "en"))
    }

    @Test
    fun commentaryGoesLast() {
        val tracks = listOf(
            track(channels = 6, label = "Director's Commentary"),
            track(channels = 2),
            track(channels = 6, isCommentary = true),
        )
        assertEquals(1, bestAudioTrack(tracks, "en"))
    }

    @Test
    fun betterCodecWinsAtEqualChannelsThenBitrate() {
        val tracks = listOf(
            track(codec = AudioCodec.AC3, channels = 6, bitrate = 640_000),
            track(codec = AudioCodec.EAC3, channels = 6, bitrate = 384_000),
            track(codec = AudioCodec.Common, channels = 6, bitrate = 900_000),
        )
        assertEquals(1, bestAudioTrack(tracks, "en"))
        val sameCodec = listOf(
            track(codec = AudioCodec.AC3, channels = 6, bitrate = 384_000),
            track(codec = AudioCodec.AC3, channels = 6, bitrate = 640_000),
        )
        assertEquals(1, bestAudioTrack(sameCodec, "en"))
    }

    @Test
    fun emptyListHasNoBest() {
        assertEquals(-1, bestAudioTrack(emptyList(), "en"))
    }
}
