package com.takeoutfm.android

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.takeoutfm.media.video.TrackPicks
import com.takeoutfm.media.video.applySubtitlePreference
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** What counts as a pick in Media3's track menus (the languages kept for the next titles). Nothing plays. */
@RunWith(AndroidJUnit4::class)
class TrackPicksTest {
    private var parameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT

    /** Just enough of a Player: its track selection parameters, which notify [picks]. */
    private val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "getTrackSelectionParameters" -> parameters
            "setTrackSelectionParameters" -> {
                parameters = args[0] as TrackSelectionParameters
                picks.onTrackSelectionParametersChanged(parameters)
                null
            }
            else -> null
        }
    } as Player

    private val audio = mutableListOf<String>()
    private val subtitles = mutableListOf<String>()
    private val picks: TrackPicks = TrackPicks(player, { audio += it }, { subtitles += it })

    private fun group(type: String, language: String) = TrackGroup(
        Format.Builder().setSampleMimeType(type).setLanguage(language).build()
    )

    private val japanese = group(MimeTypes.AUDIO_AAC, "jpn")
    private val vobsub = group(MimeTypes.APPLICATION_VOBSUB, "en")
    private val french = group(MimeTypes.APPLICATION_SUBRIP, "fr")

    private fun pick(group: TrackGroup) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group, 0))
            .build()
    }

    @Test
    fun anAudioPickIsKeptNormalized() {
        pick(japanese)
        assertEquals(listOf("ja"), audio)
        assertEquals(emptyList<String>(), subtitles)
    }

    @Test
    fun theAppsOwnChoiceIsNotAPick() {
        picks.auto { pick(japanese) }
        assertEquals(emptyList<String>(), audio)
        // and picking subtitles afterwards doesn't count the audio chosen for us
        pick(vobsub)
        assertEquals(emptyList<String>(), audio)
        assertEquals(listOf("en"), subtitles)
    }

    @Test
    fun subtitlesOffAndBack() {
        pick(french)
        // Media3's menu: None clears the override and allows only forced subtitles
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
            .build()
        pick(vobsub)
        assertEquals(listOf("fr", "", "en"), subtitles)
    }

    @Test
    fun aRememberedSubtitleAppliedByTheAppIsNotAPick() {
        picks.auto { applySubtitlePreference(player, "") }
        picks.auto { applySubtitlePreference(player, "fr") }
        assertEquals(emptyList<String>(), subtitles)
    }
}
