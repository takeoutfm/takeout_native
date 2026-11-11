package com.takeoutfm.shared.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioTrackLabelTest {

    @Test
    fun formatsFromMedia3MimeTypes() {
        assertEquals(AudioFormat.TrueHD, audioFormat("audio/true-hd"))
        assertEquals(AudioFormat.EAC3Atmos, audioFormat("audio/eac3-joc"))
        assertEquals(AudioFormat.EAC3, audioFormat("audio/eac3"))
        assertEquals(AudioFormat.AC3, audioFormat("audio/ac3"))
        assertEquals(AudioFormat.DTS, audioFormat("audio/vnd.dts"))
        assertEquals(AudioFormat.DTSHD, audioFormat("audio/vnd.dts.hd"))
        assertEquals(AudioFormat.DTSX, audioFormat("audio/vnd.dts.uhd;profile=p2"))
        assertEquals(AudioFormat.AAC, audioFormat("audio/mp4a-latm"))
        assertEquals(AudioFormat.PCM, audioFormat("audio/raw"))
        assertEquals(AudioFormat.MP3, audioFormat("audio/mpeg"))
    }

    @Test
    fun formatsFromFfmpegCodecsAndProfiles() {
        assertEquals(AudioFormat.DTSHDMA, audioFormat("dts", "DTS-HD MA"))
        assertEquals(AudioFormat.DTSX, audioFormat("dts", "DTS-HD MA + DTS:X"))
        assertEquals(AudioFormat.DTSHD, audioFormat("dts", "DTS-HD HRA"))
        assertEquals(AudioFormat.DTS, audioFormat("dts", "DTS"))
        assertEquals(AudioFormat.TrueHDAtmos, audioFormat("truehd", "Dolby TrueHD + Dolby Atmos"))
        assertEquals(AudioFormat.EAC3, audioFormat("eac3"))
        assertEquals(AudioFormat.PCM, audioFormat("pcm_s24le"))
        assertNull(audioFormat("wmav2"))
    }

    @Test
    fun formatsFromVlcDescriptions() {
        assertEquals(AudioFormat.AC3, audioFormat("A52 Audio (aka AC3)"))
        assertEquals(AudioFormat.EAC3, audioFormat("A52 B Audio (aka E-AC3)"))
        assertEquals(AudioFormat.TrueHD, audioFormat("TrueHD Audio"))
        assertEquals(AudioFormat.DTS, audioFormat("DTS Audio"))
        assertEquals(AudioFormat.AAC, audioFormat("MPEG AAC Audio"))
        assertEquals(AudioFormat.MP3, audioFormat("MPEG Audio layer 3"))
    }

    @Test
    fun formatAndChannelsAboveLanguage() {
        assertEquals(
            AudioTrackLabel("Dolby Digital · Surround 5.1", "English"),
            audioTrackLabel(AudioFormat.AC3, 6, "English", null, isCommentary = false, number = 1),
        )
        assertEquals(
            AudioTrackLabel("DTS-HD MA · Surround 7.1", "French"),
            audioTrackLabel(AudioFormat.DTSHDMA, 8, "French", "", isCommentary = false, number = 1),
        )
    }

    @Test
    fun namesThatRepeatTheFormatAreLeftOut() {
        listOf("Surround 5.1", "English", "DD 5.1", "AC3 5.1 @ 640 kbps", "English 5.1ch", "Dolby Digital").forEach {
            assertEquals(
                AudioTrackLabel("Dolby Digital · Surround 5.1", "English"),
                audioTrackLabel(AudioFormat.AC3, 6, "English", it, isCommentary = false, number = 1),
                it,
            )
        }
    }

    @Test
    fun namesThatSayMoreAreShown() {
        assertEquals(
            AudioTrackLabel("AAC · Stereo", "English · Director's Commentary"),
            audioTrackLabel(AudioFormat.AAC, 2, "English", "Director's Commentary", isCommentary = true, number = 2),
        )
        // The name already gives the language.
        assertEquals(
            AudioTrackLabel("AAC · Stereo", "English Descriptive Audio"),
            audioTrackLabel(AudioFormat.AAC, 2, "English", "English Descriptive Audio", isCommentary = false, number = 2),
        )
        assertEquals(
            AudioTrackLabel("AAC · Stereo", "English · Commentary"),
            audioTrackLabel(AudioFormat.AAC, 2, "English", "Stereo", isCommentary = true, number = 2),
        )
    }

    @Test
    fun unknownTracks() {
        assertEquals(AudioTrackLabel("Track 3", ""), audioTrackLabel(null, 0, null, null, isCommentary = false, number = 3))
        assertEquals(
            AudioTrackLabel("Original Mix", "Japanese"),
            audioTrackLabel(null, 0, "Japanese", "Original Mix", isCommentary = false, number = 1),
        )
        assertEquals(AudioTrackLabel("Surround 6.1", ""), audioTrackLabel(null, 7, null, null, isCommentary = false, number = 1))
    }
}
