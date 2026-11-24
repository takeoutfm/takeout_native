package com.takeoutfm.android

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.takeoutfm.media.video.selectSubtitleTrack
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The subtitle track chosen for a new title in the language picked last (TRACKS-6). Nothing plays. */
@RunWith(AndroidJUnit4::class)
class SubtitleSelectionTest {
    private var parameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
    private var tracks: Tracks = Tracks.EMPTY

    /** Just enough of a Player: its tracks and track selection parameters. */
    private val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "getTrackSelectionParameters" -> parameters
            "setTrackSelectionParameters" -> { parameters = args[0] as TrackSelectionParameters; null }
            "getCurrentTracks" -> tracks
            else -> null
        }
    } as Player

    private fun subtitle(language: String, forced: Boolean = false) = TrackGroup(
        Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setSelectionFlags(if (forced) C.SELECTION_FLAG_FORCED else 0)
            .build()
    )

    private fun given(vararg groups: TrackGroup) {
        tracks = Tracks(groups.map { Tracks.Group(it, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)) })
    }

    private fun selected(): TrackGroup? =
        parameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }?.mediaTrackGroup

    @Test
    fun theFirstTrackInTheLanguageThatIsNotForced() {
        val forced = subtitle("eng", forced = true)
        val full = subtitle("eng")
        given(subtitle("fr"), forced, full)
        selectSubtitleTrack(player, "en")
        assertEquals(full, selected())
    }

    @Test
    fun aForcedTrackWhenThatIsAllTheLanguageHas() {
        val forced = subtitle("fre", forced = true)
        given(subtitle("en"), forced)
        selectSubtitleTrack(player, "fr")
        assertEquals(forced, selected())
    }

    @Test
    fun noneInTheLanguageOrNoPickLeavesMedia3s() {
        given(subtitle("en"))
        selectSubtitleTrack(player, "de")
        selectSubtitleTrack(player, null)
        selectSubtitleTrack(player, "")
        assertTrue(parameters.overrides.isEmpty())
    }
}
