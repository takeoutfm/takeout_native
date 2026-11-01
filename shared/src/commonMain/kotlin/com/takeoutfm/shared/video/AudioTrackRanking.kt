package com.takeoutfm.shared.video

import kotlinx.serialization.Serializable

/** Audio codecs as the ranking orders them; each player maps its own names onto these. */
@Serializable
enum class AudioCodec(internal val rank: Int) {
    /** TrueHD, DTS-HD MA, DTS:X: lossless with object or many channels. */
    Lossless(6),
    /** E-AC-3 with Atmos (JOC). */
    EAC3Atmos(5),
    EAC3(4),
    DTS(3),
    AC3(2),
    /** FLAC and PCM. */
    Uncompressed(2),
    /** AAC, Opus, Vorbis. */
    Common(1),
    Other(0),
}

/** What the audio ranking looks at for one track. */
@Serializable
data class AudioCandidate(
    val language: String?,
    val label: String?,
    val codec: AudioCodec,
    val channels: Int,
    val bitrate: Int,
    val isDefault: Boolean,
    val isCommentary: Boolean,
)

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
        { -it.codec.rank },
        { -it.bitrate.coerceAtLeast(0) },
    )
    return candidates.indices.minWith { a, b -> comparator.compare(candidates[a], candidates[b]) }
}

/**
 * A language tag as its two-letter code where there is one: "eng", "en-US", "en_GB" and "en"
 * are all "en". Null for blank or undetermined ("und").
 */
fun normalizeLanguage(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    val language = tag.trim().replace('_', '-').substringBefore('-').lowercase()
    if (language.isEmpty() || language == "und" || language == "mul" || language == "zxx") return null
    return ThreeLetterLanguages[language] ?: language
}

/**
 * ISO 639-2 codes (both the bibliographic and terminology forms) of the languages films and
 * series come in, to their ISO 639-1 codes. Anything else is compared as it is.
 */
private val ThreeLetterLanguages: Map<String, String> = buildMap {
    fun add(two: String, vararg three: String) = three.forEach { put(it, two) }
    add("af", "afr"); add("am", "amh"); add("ar", "ara"); add("az", "aze"); add("be", "bel")
    add("bg", "bul"); add("bn", "ben"); add("bs", "bos"); add("ca", "cat"); add("cs", "cze", "ces")
    add("cy", "wel", "cym"); add("da", "dan"); add("de", "ger", "deu"); add("el", "gre", "ell")
    add("en", "eng"); add("es", "spa"); add("et", "est"); add("eu", "baq", "eus"); add("fa", "per", "fas")
    add("fi", "fin"); add("fil", "fil"); add("fr", "fre", "fra"); add("ga", "gle"); add("gl", "glg")
    add("gu", "guj"); add("he", "heb"); add("hi", "hin"); add("hr", "hrv"); add("hu", "hun")
    add("hy", "arm", "hye"); add("id", "ind"); add("is", "ice", "isl"); add("it", "ita"); add("ja", "jpn")
    add("ka", "geo", "kat"); add("kk", "kaz"); add("km", "khm"); add("kn", "kan"); add("ko", "kor")
    add("lo", "lao"); add("lt", "lit"); add("lv", "lav"); add("mk", "mac", "mkd"); add("ml", "mal")
    add("mn", "mon"); add("mr", "mar"); add("ms", "may", "msa"); add("mt", "mlt"); add("my", "bur", "mya")
    add("nb", "nob"); add("ne", "nep"); add("nl", "dut", "nld"); add("nn", "nno"); add("no", "nor")
    add("pa", "pan"); add("pl", "pol"); add("pt", "por"); add("ro", "rum", "ron"); add("ru", "rus")
    add("si", "sin"); add("sk", "slo", "slk"); add("sl", "slv"); add("sq", "alb", "sqi"); add("sr", "srp")
    add("sv", "swe"); add("sw", "swa"); add("ta", "tam"); add("te", "tel"); add("th", "tha")
    add("tl", "tgl"); add("tr", "tur"); add("uk", "ukr"); add("ur", "urd"); add("uz", "uzb")
    add("vi", "vie"); add("zh", "chi", "zho"); add("yue", "yue"); add("zu", "zul")
}
