package com.google.jetstream.presentation.screens.videoPlayer.components

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackRankingTest {
    private fun track(
        language: String? = "en",
        mime: String = MimeTypes.AUDIO_AAC,
        channels: Int = 2,
        bitrate: Int = 0,
        isDefault: Boolean = false,
        label: String? = null,
        isCommentary: Boolean = false,
    ) = AudioCandidate(language, label, mime, channels, bitrate, isDefault, isCommentary)

    @Test
    fun moreChannelsBeatsTheDefaultFlag() {
        val tracks = listOf(
            track(channels = 2, isDefault = true),
            track(mime = MimeTypes.AUDIO_AC3, channels = 6),
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
            track(mime = MimeTypes.AUDIO_AC3, channels = 6, bitrate = 640_000),
            track(mime = MimeTypes.AUDIO_E_AC3, channels = 6, bitrate = 384_000),
            track(mime = MimeTypes.AUDIO_AAC, channels = 6, bitrate = 900_000),
        )
        assertEquals(1, bestAudioTrack(tracks, "en"))
        val sameCodec = listOf(
            track(mime = MimeTypes.AUDIO_AC3, channels = 6, bitrate = 384_000),
            track(mime = MimeTypes.AUDIO_AC3, channels = 6, bitrate = 640_000),
        )
        assertEquals(1, bestAudioTrack(sameCodec, "en"))
    }

    @Test
    fun emptyListHasNoBest() {
        assertEquals(-1, bestAudioTrack(emptyList(), "en"))
    }
}
