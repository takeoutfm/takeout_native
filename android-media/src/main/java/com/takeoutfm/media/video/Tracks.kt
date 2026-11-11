package com.takeoutfm.media.video

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import com.takeoutfm.shared.video.audioFormat
import com.takeoutfm.shared.video.audioTrackLabel
import com.takeoutfm.shared.video.bestAudioTrack
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
