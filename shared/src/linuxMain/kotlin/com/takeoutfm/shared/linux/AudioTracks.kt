package com.takeoutfm.shared.linux

import com.takeoutfm.shared.video.AudioCandidate
import com.takeoutfm.shared.video.AudioCodec
import com.takeoutfm.shared.video.AudioTrackLabel
import com.takeoutfm.shared.video.audioFormat
import com.takeoutfm.shared.video.audioTrackLabel
import com.takeoutfm.shared.video.bestAudioTrack
import kotlinx.serialization.Serializable

/** One of mpv's audio tracks as `VideoView` reports it (its track-list fields). */
@Serializable
internal data class MpvAudioTrack(
    val codec: String = "",
    val profile: String = "",
    val channels: Int = 0,
    val language: String? = null,
    /** The language as the app's locale names it ("English"); the core has no locale data. */
    val languageName: String? = null,
    /** The file's own title for the track. */
    val title: String? = null,
    val bitrate: Int = 0,
    val isDefault: Boolean = false,
)

/** The pickers' labels for each track, and the best one (-1 when none). */
@Serializable
internal data class AudioTracks(val labels: List<AudioTrackLabel>, val best: Int)

internal fun audioTracks(tracks: List<MpvAudioTrack>, preferredLanguage: String?): AudioTracks {
    val formats = tracks.map { audioFormat(it.codec, it.profile) }
    val commentary = tracks.map { it.title?.contains("commentary", ignoreCase = true) == true }
    val labels = tracks.mapIndexed { i, t ->
        audioTrackLabel(formats[i], t.channels, t.languageName, t.title, commentary[i], number = i + 1)
    }
    val candidates = tracks.mapIndexed { i, t ->
        AudioCandidate(
            language = t.language?.takeIf { it.isNotEmpty() },
            label = t.title?.takeIf { it.isNotEmpty() },
            codec = formats[i]?.codec ?: AudioCodec.Other,
            channels = t.channels,
            bitrate = t.bitrate,
            isDefault = t.isDefault,
            isCommentary = commentary[i],
        )
    }
    return AudioTracks(labels, bestAudioTrack(candidates, preferredLanguage))
}
