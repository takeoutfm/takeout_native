package com.takeoutfm.shared.video

/**
 * A subtitle format as the pickers name it ("SRT", "PGS"), from what the player calls the codec:
 * an ffmpeg / mpv name ("subrip", "hdmv_pgs_subtitle") or VLC's description ("Blu-ray
 * Presentation Graphic Subtitles"). Null when it's none of these.
 */
fun subtitleFormat(codec: String): String? {
    val c = codec.lowercase()
    return when {
        c == "subrip" || c == "srt" || "subrip" in c -> "SRT"
        c == "ass" || "advanced substation" in c -> "ASS"
        c == "ssa" || "substation alpha" in c -> "SSA"
        "webvtt" in c || "wvtt" in c -> "WebVTT"
        "pgs" in c || "presentation graphic" in c -> "PGS"
        c == "dvd_subtitle" || "dvd sub" in c || "vobsub" in c -> "VobSub"
        "dvb_subtitle" in c || "dvb sub" in c -> "DVB"
        c == "mov_text" || "timed text" in c || "tx3g" in c -> "Timed Text"
        // VLC's plain text subtitles (from SRT and the like)
        "text subtitles" in c -> "Text"
        else -> null
    }
}

/**
 * A subtitle track as the pickers list it (TRACKS-3): language, the file's name for it, Forced,
 * SDH and the format, e.g. "English · SDH · PGS"; "Track [number]" when nothing is known.
 * Where the player has no flags (VLC), [forced] and [sdh] come from the name's words, and
 * nothing the name already says is repeated.
 */
fun subtitleTrackLabel(
    languageName: String?,
    name: String,
    forced: Boolean = false,
    sdh: Boolean = false,
    format: String?,
    number: Int,
): String {
    val title = name.trim()
    val lower = title.lowercase()
    val parts = buildList {
        if (!languageName.isNullOrBlank() && languageName.lowercase() !in lower) add(languageName)
        if (title.isNotEmpty()) add(title)
        if (forced && "forced" !in lower) add("Forced")
        if (sdh && !lower.containsWord("sdh")) add("SDH")
        if (!format.isNullOrEmpty() && format.lowercase() !in lower) add(format)
    }.distinct()
    return if (parts.isEmpty()) "Track $number" else parts.joinToString(" · ")
}

/**
 * VLC's own name for a track when it made it up ("Track 1 - [English]", "Track 2"), which says
 * nothing the language doesn't: the label leaves it out.
 */
fun isGeneratedTrackName(name: String): Boolean =
    Regex("""^Track \d+( - \[[^\]]*\])?$""").matches(name.trim())

private fun String.containsWord(word: String): Boolean =
    Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(this)

/** A subtitle track as [pickSubtitleTrack] sees it. */
data class SubtitleCandidate(
    val id: Int,
    /** Normalised ("en"), or null when the track has none. */
    val language: String?,
    /** Where the player reports it (mpv, Media3); else from the track's name ([looksForced]). */
    val forced: Boolean,
)

/**
 * The subtitle track for a new title in the [language] picked last (TRACKS-6): the first in it
 * that isn't forced, as forced tracks only subtitle the odd foreign line; a forced one only when
 * that's all the language has. Null when the title has none in it (the app then leaves the
 * title's own default). "Off" and "never picked" are the app's to handle before calling this.
 */
fun pickSubtitleTrack(tracks: List<SubtitleCandidate>, language: String): Int? {
    val inLanguage = tracks.filter { it.language == language }
    return (inLanguage.firstOrNull { !it.forced } ?: inLanguage.firstOrNull())?.id
}

/** Whether a track's name says it's forced, for players that give no flag (VLC). */
fun looksForced(name: String): Boolean = Regex("""\bforced\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)
