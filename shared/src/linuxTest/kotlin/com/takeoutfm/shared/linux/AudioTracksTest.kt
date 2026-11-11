package com.takeoutfm.shared.linux

import com.takeoutfm.shared.video.AudioTrackLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioTracksTest {
    @Test
    fun labelsAndBestFromMpvTracks() {
        val result = audioTracks(
            listOf(
                MpvAudioTrack(codec = "ac3", channels = 2, language = "eng", languageName = "English", title = "Commentary"),
                MpvAudioTrack(codec = "dts", profile = "DTS-HD MA", channels = 8, language = "eng", languageName = "English", title = "Surround 7.1"),
                MpvAudioTrack(codec = "eac3", channels = 6, language = "fre", languageName = "French", isDefault = true),
            ),
            preferredLanguage = "en_US",
        )
        assertEquals(
            listOf(
                AudioTrackLabel("Dolby Digital · Stereo", "English · Commentary"),
                AudioTrackLabel("DTS-HD MA · Surround 7.1", "English"),
                AudioTrackLabel("Dolby Digital Plus · Surround 5.1", "French"),
            ),
            result.labels,
        )
        assertEquals(1, result.best)
    }

    @Test
    fun bridgeJsonReadsVideoViewFields() {
        val track = bridgeJson.decodeFromString<MpvAudioTrack>(
            """{"codec":"truehd","profile":"","channels":8,"language":null,"languageName":null,"title":null,"bitrate":0,"isDefault":false}"""
        )
        assertEquals(AudioTrackLabel("Dolby TrueHD · Surround 7.1", ""), audioTracks(listOf(track), null).labels.single())
    }
}
