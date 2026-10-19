package com.google.jetstream.presentation.screens.videoPlayer.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.google.jetstream.R
import com.google.jetstream.presentation.theme.JetStreamCardShape
import java.util.Locale

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

/** Applies the remembered subtitle choice ([TakeoutSettings.subtitleLanguage]) to [player]. */
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

@Composable
fun TrackPickerDialog(
    title: String,
    options: List<TrackOption>,
    emptyText: String,
    onSelect: (TrackOption) -> Unit,
    onDismissRequest: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = JetStreamCardShape,
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(min = 360.dp, max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 8.dp, bottom = 16.dp)
                )
                if (options.isEmpty()) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                // Start focus on the current choice so the D-pad lands where the user is.
                val focusIndex = options.indexOfFirst { it.isSelected }.coerceAtLeast(0)
                val focusRequester = remember { FocusRequester() }
                LazyColumn {
                    itemsIndexed(options) { index, option ->
                        ListItem(
                            selected = option.isSelected,
                            enabled = option.isSupported,
                            onClick = { onSelect(option) },
                            headlineContent = { Text(option.label) },
                            supportingContent = if (option.isSupported) {
                                null
                            } else {
                                { Text(stringResource(R.string.track_not_supported)) }
                            },
                            trailingContent = if (option.isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (index == focusIndex) {
                                        Modifier.focusRequester(focusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
                if (options.isNotEmpty()) {
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                }
            }
        }
    }
}

/** Renders the player's current subtitle cues, text and bitmap alike. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerSubtitles(player: Player, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember {
        SubtitleView(context).apply {
            setStyle(
                CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK,
                    null
                )
            )
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                view.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        view.setCues(player.currentCues.cues)
        onDispose { player.removeListener(listener) }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
