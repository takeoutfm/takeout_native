package com.takeoutfm.shared.linux

import com.takeoutfm.shared.video.SubtitleCandidate
import com.takeoutfm.shared.video.normalizeLanguage
import com.takeoutfm.shared.video.pickSubtitleTrack
import kotlinx.serialization.Serializable

/** One of mpv's subtitle tracks as `VideoView` reports it. */
@Serializable
internal data class MpvSubtitleTrack(
    val id: Int,
    /** As the file gives it: "eng", "en", "en-US"… */
    val language: String? = null,
    /** mpv's `forced` flag. */
    val forced: Boolean = false,
)

/**
 * The track to show from [tracks] for a new title, with [language] the one picked last as the
 * file gave it (TRACKS-6): the shared rule, both sides normalised so "eng" finds "en". Null when
 * the title has none in it.
 */
internal fun subtitleTrack(tracks: List<MpvSubtitleTrack>, language: String): Int? {
    val wanted = normalizeLanguage(language) ?: return null
    return pickSubtitleTrack(
        tracks.map { SubtitleCandidate(it.id, normalizeLanguage(it.language), it.forced) },
        wanted,
    )
}
