package com.takeoutfm.shared.listen

private const val FourMinutesMs = 4 * 60_000L

/** ListenBrainz ignores anything shorter. */
private const val MinListenMs = 30_000L

/** The Takeout apps' rule: 4 minutes in or half way through, whichever comes first. */
fun countsAsListened(playedMs: Long, durationMs: Long): Boolean =
    playedMs >= FourMinutesMs || (durationMs > 0 && playedMs >= durationMs / 2)

/**
 * A song on a live stream, judged when the stream's title changes (its length is unknown):
 * heard from its start (at least 30 s), or for 4 minutes. The song playing when you tune in
 * wasn't heard from its start.
 */
fun countsAsStreamListen(heardFromStart: Boolean, heardMs: Long): Boolean =
    heardMs >= FourMinutesMs || (heardFromStart && heardMs >= MinListenMs)

/**
 * A stream's ICY title as artist and title: "Artist - Title" (split at the first " - "); null
 * when it isn't in that form (station IDs, ads, show names).
 */
fun parseStreamTitle(icy: String): Pair<String, String>? {
    val i = icy.indexOf(" - ")
    if (i <= 0) return null
    val artist = icy.substring(0, i).trim()
    val title = icy.substring(i + 3).trim()
    return if (artist.isEmpty() || title.isEmpty()) null else artist to title
}
