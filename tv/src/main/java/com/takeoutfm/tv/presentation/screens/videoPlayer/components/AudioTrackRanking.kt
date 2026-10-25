package com.takeoutfm.tv.presentation.screens.videoPlayer.components

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

/** What the audio ranking looks at for one track; built from a Media3 [Format]. */
data class AudioCandidate(
    val language: String?,
    val label: String?,
    val mimeType: String?,
    val channels: Int,
    val bitrate: Int,
    val isDefault: Boolean,
    val isCommentary: Boolean,
) {
    companion object {
        fun from(format: Format) = AudioCandidate(
            language = format.language,
            label = format.label,
            mimeType = format.sampleMimeType,
            channels = format.channelCount,
            bitrate = format.bitrate,
            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            isCommentary = format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0,
        )
    }
}

/**
 * Index of the best audio track among [candidates] (which should already be limited to tracks
 * the device can play), or -1 if there are none. In order:
 * 1. the preferred language (the last one picked, else the device's), falling back to the
 *    file's default track when no track has that language;
 * 2. not commentary;
 * 3. most channels;
 * 4. better codec at equal channels (TrueHD/DTS-HD > E-AC-3 > DTS > AC-3 > AAC/Opus/…);
 * 5. higher bitrate.
 */
fun bestAudioTrack(candidates: List<AudioCandidate>, preferredLanguage: String?): Int {
    if (candidates.isEmpty()) return -1
    val wanted = normalizeLanguage(preferredLanguage)
    val anyMatches = wanted != null && candidates.any { normalizeLanguage(it.language) == wanted }
    val comparator = compareBy<AudioCandidate>(
        { if (anyMatches) normalizeLanguage(it.language) != wanted else !it.isDefault },
        { it.isCommentary || it.label?.contains("commentary", ignoreCase = true) == true },
        { -it.channels.coerceAtLeast(0) },
        { -codecRank(it.mimeType) },
        { -it.bitrate.coerceAtLeast(0) },
    )
    return candidates.indices.minWith { a, b -> comparator.compare(candidates[a], candidates[b]) }
}

/** "eng", "en-US" and "en" all compare equal; "und"/blank mean unknown. */
fun normalizeLanguage(tag: String?): String? {
    if (tag.isNullOrBlank() || tag == C.LANGUAGE_UNDETERMINED) return null
    val language = Locale.forLanguageTag(tag.replace('_', '-')).language.ifEmpty { tag }
    // ISO 639-2 codes ("eng") -> 639-1 ("en") where Locale knows them.
    return Locale.getAvailableLocales().firstOrNull {
        runCatching { it.isO3Language }.getOrNull() == language
    }?.language ?: language.lowercase(Locale.ROOT)
}

private fun codecRank(mimeType: String?): Int = when (mimeType) {
    MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> 6
    MimeTypes.AUDIO_E_AC3_JOC -> 5
    MimeTypes.AUDIO_E_AC3 -> 4
    MimeTypes.AUDIO_DTS -> 3
    MimeTypes.AUDIO_AC3 -> 2
    MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_RAW -> 2
    MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS -> 1
    else -> 0
}
