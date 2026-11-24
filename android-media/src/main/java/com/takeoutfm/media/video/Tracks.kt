package com.takeoutfm.media.video

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import com.takeoutfm.shared.video.audioFormat
import com.takeoutfm.shared.video.audioTrackLabel
import com.takeoutfm.shared.video.bestAudioTrack
import com.takeoutfm.shared.video.SubtitleCandidate
import com.takeoutfm.shared.video.normalizeLanguage
import com.takeoutfm.shared.video.pickSubtitleTrack
import java.util.Locale

// Video track choices for the apps' pickers: embedded audio and subtitle tracks (no
// server-side subtitles), playback speed, the remembered subtitle language and the best audio
// track. UI-free; each app draws its own picker.

/** Which track picker is open. */
enum class TrackPicker { Audio, Subtitles, Speed }

/** One choice in a track picker; [select] applies it to the player. */
data class TrackOption(
    val label: String,
    /** A second line under [label]: an audio track's language and name ("English · Commentary"). */
    val detail: String? = null,
    val isSelected: Boolean,
    /** The track's language, if it has one ("" for the subtitles Off option). */
    val language: String? = null,
    /** False when this device can't decode it; listed (greyed out) but not selectable. */
    val isSupported: Boolean = true,
    val select: (Player) -> Unit,
)

val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** "0.5×" … "2×", with "Normal" for 1×. */
fun speedOptions(player: Player, normalLabel: String): List<TrackOption> {
    val current = player.playbackParameters.speed
    return PlaybackSpeeds.map { speed ->
        TrackOption(
            label = if (speed == 1f) normalLabel else "${speed.toString().removeSuffix(".0")}×",
            isSelected = kotlin.math.abs(current - speed) < 0.01f,
            select = { p -> p.setPlaybackSpeed(speed) }
        )
    }
}

/** Applies a remembered subtitle choice to [player]: a language, "" for off, null for no preference. */
fun applySubtitlePreference(player: Player, language: String?) {
    language ?: return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .apply {
            if (language.isEmpty()) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setPreferredTextLanguage(language)
            }
        }
        .build()
}

/** Embedded audio tracks, one option per playable track. */
fun audioTrackOptions(player: Player): List<TrackOption> =
    trackOptions(player, C.TRACK_TYPE_AUDIO)

/** "Off" followed by the embedded subtitle tracks. */
fun subtitleTrackOptions(player: Player, offLabel: String): List<TrackOption> {
    val tracks = trackOptions(player, C.TRACK_TYPE_TEXT)
    val off = TrackOption(
        label = offLabel,
        isSelected = tracks.none { it.isSelected },
        language = "",
        select = { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
    )
    return listOf(off) + tracks
}

private fun trackOptions(player: Player, type: @C.TrackType Int): List<TrackOption> {
    val groups = player.currentTracks.groups.filter { it.type == type }
    var number = 0
    return groups.flatMap { group ->
        (0 until group.length).map { i ->
            number++
            val override = TrackSelectionOverride(group.mediaTrackGroup, i)
            val format = group.getTrackFormat(i)
            val audio = if (type == C.TRACK_TYPE_AUDIO) format.audioLabel(number) else null
            TrackOption(
                label = audio?.title ?: format.describe(number),
                detail = audio?.detail?.takeIf { it.isNotEmpty() },
                isSelected = group.isTrackSelected(i),
                language = format.language,
                isSupported = group.isTrackSupported(i),
                select = { p ->
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(override)
                        .build()
                }
            )
        }
    }
}

/** The shared audio label: "Dolby Digital · Surround 5.1" over "English". */
private fun Format.audioLabel(number: Int) = audioTrackLabel(
    format = sampleMimeType?.let { audioFormat(it) },
    channels = channelCount,
    languageName = languageName(),
    name = label,
    isCommentary = roleFlags and C.ROLE_FLAG_COMMENTARY != 0,
    number = number,
)

private fun Format.languageName(): String? = language
    ?.takeIf { it.isNotEmpty() && it != C.LANGUAGE_UNDETERMINED }
    ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it } }

/** A subtitle track, e.g. "English · SDH · PGS". */
private fun Format.describe(number: Int): String {
    val parts = mutableListOf<String>()
    val name = label?.takeIf { it.isNotBlank() }
    val languageName = languageName()
    if (languageName != null && (name == null || !name.contains(languageName, ignoreCase = true))) {
        parts += languageName
    }
    if (name != null) parts += name
    if (selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Forced"
    if (roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) {
        parts += "SDH"
    }
    subtitleFormat(sampleMimeType)?.let { parts += it }
    return parts.distinct().joinToString(" · ").ifEmpty { "Track $number" }
}

private fun subtitleFormat(mimeType: String?): String? = when (mimeType) {
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_SSA -> "SSA"
    MimeTypes.TEXT_VTT -> "WebVTT"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    else -> null
}

/**
 * Selects the best playable audio track for this title (the shared [bestAudioTrack]). Returns false
 * while there are no audio tracks yet, so the caller can try again on the next tracks change.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun selectBestAudioTrack(player: Player, preferredLanguage: String?): Boolean {
    val playable = player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
    if (playable.isEmpty()) return false
    val best = bestAudioTrack(
        playable.map { (group, i) -> group.getTrackFormat(i).toAudioCandidate() },
        preferredLanguage ?: java.util.Locale.getDefault().toLanguageTag()
    )
    val (group, index) = playable[best]
    if (!group.isTrackSelected(index)) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }
    return true
}

/**
 * Once the title's tracks are known, selects the subtitle track the shared rule picks in the
 * [language] picked last (TRACKS-6): the first in it that isn't forced, a forced one only when
 * that's all it has. [applySubtitlePreference] only gives Media3 the language, and Media3 may
 * take a forced track. Nothing for no preference, off (already applied), or none in it.
 */
fun selectSubtitleTrack(player: Player, language: String?) {
    val wanted = normalizeLanguage(language) ?: return
    val playable = player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
    val pick = pickSubtitleTrack(
        playable.mapIndexed { i, (group, index) ->
            val format = group.getTrackFormat(index)
            SubtitleCandidate(i, normalizeLanguage(format.language), format.selectionFlags and C.SELECTION_FLAG_FORCED != 0)
        },
        wanted,
    ) ?: return
    val (group, index) = playable[pick]
    if (!group.isTrackSelected(index)) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }
}

/**
 * Hears the audio and subtitle languages picked in Media3's own track menus (a PlayerView's
 * settings) for the titles after: [onAudio] gets a language, [onSubtitle] a language or "" for
 * off. What the app selects itself goes through [auto] and isn't a pick — the best audio track
 * would otherwise become the preferred language. Add it as a listener of [player].
 */
class TrackPicks(
    private val player: Player,
    private val onAudio: (String) -> Unit,
    private val onSubtitle: (String) -> Unit,
) : Player.Listener {
    private var last: TrackSelectionParameters = player.trackSelectionParameters
    private var applying = false

    /** Runs [block], which changes the player's track selection itself. */
    fun <T> auto(block: () -> T): T {
        applying = true
        try {
            return block()
        } finally {
            applying = false
            last = player.trackSelectionParameters
        }
    }

    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
        val before = last
        last = parameters
        if (applying || parameters == before) return
        val audio = parameters.override(C.TRACK_TYPE_AUDIO)
        if (audio != null && audio != before.override(C.TRACK_TYPE_AUDIO)) {
            normalizeLanguage(audio.mediaTrackGroup.getFormat(0).language)?.let(onAudio)
        }
        val text = parameters.override(C.TRACK_TYPE_TEXT)
        if (text != null && text != before.override(C.TRACK_TYPE_TEXT)) {
            text.mediaTrackGroup.getFormat(0).language?.takeIf { it.isNotEmpty() }?.let(onSubtitle)
        } else if (text == null && parameters.subtitlesOff() && !before.subtitlesOff()) {
            onSubtitle("")
        }
    }

    private fun TrackSelectionParameters.override(type: @C.TrackType Int) =
        overrides.values.firstOrNull { it.type == type }

    /** Off in Media3's menu: text disabled, or only forced subtitles allowed. */
    private fun TrackSelectionParameters.subtitlesOff() =
        C.TRACK_TYPE_TEXT in disabledTrackTypes || ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT != 0
}
