package com.takeoutfm.media.video

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.takeoutfm.shared.video.AudioCandidate
import com.takeoutfm.shared.video.AudioCodec

/** A Media3 audio [Format] as the shared ranking's candidate (see `bestAudioTrack`). */
fun Format.toAudioCandidate() = AudioCandidate(
    language = language,
    label = label,
    codec = audioCodec(sampleMimeType),
    channels = channelCount,
    bitrate = bitrate,
    isDefault = selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
    isCommentary = roleFlags and C.ROLE_FLAG_COMMENTARY != 0,
)

private fun audioCodec(mimeType: String?): AudioCodec = when (mimeType) {
    MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> AudioCodec.Lossless
    MimeTypes.AUDIO_E_AC3_JOC -> AudioCodec.EAC3Atmos
    MimeTypes.AUDIO_E_AC3 -> AudioCodec.EAC3
    MimeTypes.AUDIO_DTS -> AudioCodec.DTS
    MimeTypes.AUDIO_AC3 -> AudioCodec.AC3
    MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_RAW -> AudioCodec.Uncompressed
    MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS -> AudioCodec.Common
    else -> AudioCodec.Other
}
