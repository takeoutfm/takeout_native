package com.takeoutfm.media.video

import androidx.media3.common.C
import androidx.media3.common.Format
import com.takeoutfm.shared.video.AudioCandidate
import com.takeoutfm.shared.video.AudioCodec
import com.takeoutfm.shared.video.audioFormat

/** A Media3 audio [Format] as the shared ranking's candidate (see `bestAudioTrack`). */
fun Format.toAudioCandidate() = AudioCandidate(
    language = language,
    label = label,
    codec = sampleMimeType?.let { audioFormat(it)?.codec } ?: AudioCodec.Other,
    channels = channelCount,
    bitrate = bitrate,
    isDefault = selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
    isCommentary = roleFlags and C.ROLE_FLAG_COMMENTARY != 0,
)
