package com.takeoutfm.shared.video

import kotlinx.serialization.Serializable

/**
 * An audio format as the track pickers name it; [codec] is how the ranking ([bestAudioTrack])
 * orders it. Each player finds it from its own codec name with [audioFormat].
 */
enum class AudioFormat(val title: String, val codec: AudioCodec) {
    TrueHDAtmos("Dolby TrueHD Atmos", AudioCodec.Lossless),
    TrueHD("Dolby TrueHD", AudioCodec.Lossless),
    DTSX("DTS:X", AudioCodec.Lossless),
    DTSHDMA("DTS-HD MA", AudioCodec.Lossless),
    /** DTS-HD where the player doesn't say which (Media3), or High Resolution. */
    DTSHD("DTS-HD", AudioCodec.Lossless),
    DTS("DTS", AudioCodec.DTS),
    /** DTS-HD's lossy low-bitrate profile (LBR). */
    DTSExpress("DTS Express", AudioCodec.DTS),
    EAC3Atmos("Dolby Digital Plus Atmos", AudioCodec.EAC3Atmos),
    EAC3("Dolby Digital Plus", AudioCodec.EAC3),
    AC3("Dolby Digital", AudioCodec.AC3),
    FLAC("FLAC", AudioCodec.Uncompressed),
    ALAC("ALAC", AudioCodec.Uncompressed),
    PCM("PCM", AudioCodec.Uncompressed),
    AAC("AAC", AudioCodec.Common),
    Opus("Opus", AudioCodec.Common),
    Vorbis("Vorbis", AudioCodec.Common),
    MP3("MP3", AudioCodec.Other),
}

/**
 * The format of an audio track from what its player calls the codec: a Media3 MIME type
 * ("audio/eac3-joc"), an ffmpeg / mpv codec and profile ("dts", "DTS-HD MA") or a VLC codec
 * description ("A52 B Audio (aka E-AC3)"). Null when it's none of these.
 */
fun audioFormat(codec: String, profile: String = ""): AudioFormat? {
    val c = codec.lowercase()
    val p = profile.lowercase()
    val atmos = "atmos" in p || "joc" in c
    return when {
        "truehd" in c || "true-hd" in c || "mlp" in c ->
            if (atmos) AudioFormat.TrueHDAtmos else AudioFormat.TrueHD
        "eac3" in c || "e-ac3" in c || "e-ac-3" in c ->
            if (atmos) AudioFormat.EAC3Atmos else AudioFormat.EAC3
        "ac3" in c || "ac-3" in c || "a52" in c -> AudioFormat.AC3
        "dts" in c -> when {
            ":x" in p || "uhd" in c -> AudioFormat.DTSX
            "lbr" in c || "express" in p -> AudioFormat.DTSExpress
            "ma" in p.split(Regex("[^a-z]+")) -> AudioFormat.DTSHDMA
            "hd" in c || "dts-hd" in p -> AudioFormat.DTSHD
            else -> AudioFormat.DTS
        }
        "flac" in c -> AudioFormat.FLAC
        "alac" in c -> AudioFormat.ALAC
        "pcm" in c || c == "audio/raw" -> AudioFormat.PCM
        "aac" in c || "mp4a" in c -> AudioFormat.AAC
        "opus" in c -> AudioFormat.Opus
        "vorbis" in c -> AudioFormat.Vorbis
        "mp3" in c || c == "audio/mpeg" || "mpga" in c || "layer 3" in c -> AudioFormat.MP3
        else -> null
    }
}

/** "Mono", "Stereo", "Surround 5.1" …; null when the count isn't known. */
fun channelLayout(channels: Int): String? = when {
    channels <= 0 -> null
    channels == 1 -> "Mono"
    channels == 2 -> "Stereo"
    channels == 6 -> "Surround 5.1"
    channels == 7 -> "Surround 6.1"
    channels == 8 -> "Surround 7.1"
    else -> "$channels channels"
}

/**
 * How a picker shows one audio track: [title] is the format ("Dolby Digital · Surround 5.1"),
 * [detail] the language and anything the file's own name for it adds ("English · Director's
 * Commentary"), possibly empty.
 */
@Serializable
data class AudioTrackLabel(val title: String, val detail: String)

/**
 * The label for an audio track. [languageName] is the track's language as the device names it
 * ("English"), [name] the file's own title for the track, which is shown only when it says more
 * than the format, channels and language do (files often call every track "Surround 5.1");
 * [number] (from 1) names a track nothing else is known about.
 */
fun audioTrackLabel(
    format: AudioFormat?,
    channels: Int,
    languageName: String?,
    name: String?,
    isCommentary: Boolean,
    number: Int,
): AudioTrackLabel {
    val title = listOfNotNull(format?.title, channelLayout(channels)).joinToString(" · ")
    val language = languageName?.trim()?.takeIf { it.isNotEmpty() }
    val known = words(title) + words(language.orEmpty())
    val extra = name?.trim()?.takeIf { n -> words(n).any { it !in known && it !in FormatWords && !it.isNumber() } }
    val detail = buildList {
        if (language != null && extra?.contains(language, ignoreCase = true) != true) add(language)
        if (extra != null) add(extra)
        if (isCommentary && extra?.contains("commentary", ignoreCase = true) != true) add("Commentary")
    }
    return AudioTrackLabel(
        title = title.ifEmpty { extra ?: "Track $number" },
        detail = (if (title.isEmpty() && extra != null) detail - extra else detail).joinToString(" · "),
    )
}

private fun words(text: String): Set<String> =
    text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .split(' ').filter { it.isNotEmpty() }.toSet()

private fun String.isNumber() = all { it.isDigit() } || removeSuffix("ch").let { it.isNotEmpty() && it.all(Char::isDigit) }

/** Words track names use for what the title already says ("DD 5.1", "Surround", "AC3"). */
private val FormatWords = setOf(
    "audio", "track", "sound", "surround", "stereo", "mono", "ch", "channel", "channels",
    "dolby", "digital", "plus", "dd", "ddp", "ac3", "eac3", "e", "ac", "atmos", "truehd", "true", "hd",
    "dts", "ma", "hra", "x", "es", "aac", "lc", "flac", "alac", "pcm", "lpcm", "opus", "vorbis", "mp3",
    "lossless", "kbps", "kb", "s", "bit", "khz",
)
