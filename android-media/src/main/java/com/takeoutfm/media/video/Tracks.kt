package com.takeoutfm.media.video

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import java.util.Locale

// Video track choices for the apps' pickers: embedded audio and subtitle tracks (no
// server-side subtitles), playback speed, the remembered subtitle language and the best audio
// track. UI-free; each app draws its own picker.

/** Which track picker is open. */
enum class TrackPicker { Audio, Subtitles, Speed }

/** One choice in a track picker; [select] applies it to the player. */
data class TrackOption(
    val label: String,
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
            TrackOption(
                label = group.getTrackFormat(i).describe(type, number),
                isSelected = group.isTrackSelected(i),
                language = group.getTrackFormat(i).language,
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

/** e.g. "English · 5.1 · E-AC-3" or "English · SDH · PGS". */
private fun Format.describe(type: @C.TrackType Int, number: Int): String {
    val parts = mutableListOf<String>()
    val name = label?.takeIf { it.isNotBlank() }
    val languageName = language
        ?.takeIf { it.isNotEmpty() && it != C.LANGUAGE_UNDETERMINED }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it } }
    if (languageName != null && (name == null || !name.contains(languageName, ignoreCase = true))) {
        parts += languageName
    }
    if (name != null) parts += name
    if (type == C.TRACK_TYPE_AUDIO) {
        channelLayout(channelCount)?.let { parts += it }
    } else {
        if (selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Forced"
        if (roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) {
            parts += "SDH"
        }
    }
    codecName(sampleMimeType)?.let { parts += it }
    return parts.distinct().joinToString(" · ").ifEmpty { "Track $number" }
}

private fun channelLayout(channels: Int): String? = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    Format.NO_VALUE -> null
    else -> "$channels ch"
}

private fun codecName(mimeType: String?): String? = when (mimeType) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "AC-3"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_SSA -> "SSA"
    MimeTypes.TEXT_VTT -> "WebVTT"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    else -> null
}

/**
 * Selects the best playable audio track for this title (see [bestAudioTrack]). Returns false
 * while there are no audio tracks yet, so the caller can try again on the next tracks change.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun selectBestAudioTrack(player: Player, preferredLanguage: String?): Boolean {
    val playable = player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
    if (playable.isEmpty()) return false
    val best = bestAudioTrack(
        playable.map { (group, i) -> AudioCandidate.from(group.getTrackFormat(i)) },
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
