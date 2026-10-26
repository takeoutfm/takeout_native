package com.takeoutfm.mobile.voice

/** What a voice or app-action request asks for. */
sealed interface VoiceRequest {
    /** Play what the server finds for this search ref, titled [title]. */
    data class PlayRef(val ref: String, val title: String) : VoiceRequest
    data class PlayStation(val name: String) : VoiceRequest
    data class PlayMovie(val title: String) : VoiceRequest
    data object Play : VoiceRequest
    data object Pause : VoiceRequest
    data object Next : VoiceRequest
}

/**
 * Turns Assistant requests into [VoiceRequest]s: `MEDIA_PLAY_FROM_SEARCH` ("play Beck on
 * TakeoutFM", with artist / album / song focus when Assistant knows it) and the Takeout app
 * actions the Flutter app declares (`com.takeoutfm.action.*`), with the same server search
 * refs. [extras] are the intent's string extras.
 */
object VoiceRequests {
    const val ActionPrefix = "com.takeoutfm.action."
    const val PlayFromSearch = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

    // android.provider.MediaStore / SearchManager keys, as plain strings so this stays testable.
    const val ExtraQuery = "query"
    const val ExtraFocus = "android.intent.extra.focus"
    const val ExtraArtist = "android.intent.extra.artist"
    const val ExtraAlbum = "android.intent.extra.album"
    const val ExtraTitle = "android.intent.extra.title"
    private const val FocusArtist = "vnd.android.cursor.item/artist"
    private const val FocusAlbum = "vnd.android.cursor.item/album"
    private const val FocusAudio = "vnd.android.cursor.item/audio"

    fun parse(action: String?, extras: Map<String, String?>): VoiceRequest? {
        fun x(key: String) = extras[key]?.trim()?.takeIf { it.isNotEmpty() }
        return when (action) {
            PlayFromSearch -> fromSearch(x(ExtraQuery), x(ExtraFocus), x(ExtraArtist), x(ExtraAlbum), x(ExtraTitle))
            "${ActionPrefix}PLAY_ARTIST", "${ActionPrefix}PLAY_ARTIST_RADIO" ->
                x("artist")?.let { radio(it) }
            "${ActionPrefix}PLAY_ARTIST_SONG" -> both(x("artist"), x("song")) { a, s -> song(a, s) }
            "${ActionPrefix}PLAY_ARTIST_ALBUM" -> both(x("artist"), x("album")) { a, al -> album(a, al) }
            "${ActionPrefix}PLAY_ARTIST_POPULAR_SONGS" ->
                x("artist")?.let { VoiceRequest.PlayRef(ref("+artist:${q(it)} +popularity:<11"), it) }
            "${ActionPrefix}PLAY_ALBUM" -> x("album")?.let { VoiceRequest.PlayRef(ref("release:${q(it)}"), it) }
            "${ActionPrefix}PLAY_SONG" -> x("song")?.let { VoiceRequest.PlayRef(ref("title:${q(it)}"), it) }
            "${ActionPrefix}PLAY_RADIO" -> x("station")?.let { VoiceRequest.PlayStation(it) }
            "${ActionPrefix}PLAY_SEARCH" -> x("q")?.let { plain(it) }
            "${ActionPrefix}PLAYER_PLAY" -> VoiceRequest.Play
            "${ActionPrefix}PLAYER_PAUSE" -> VoiceRequest.Pause
            "${ActionPrefix}PLAYER_NEXT" -> VoiceRequest.Next
            "${ActionPrefix}PLAY_MOVIE" -> x("title")?.let { VoiceRequest.PlayMovie(it) }
            else -> null
        }
    }

    private fun fromSearch(query: String?, focus: String?, artist: String?, album: String?, title: String?): VoiceRequest =
        when {
            // "Play music on TakeoutFM": carry on with what was playing.
            query == null && artist == null && album == null && title == null -> VoiceRequest.Play
            focus == FocusAudio && title != null && artist != null -> song(artist, title)
            focus == FocusAlbum && album != null && artist != null -> album(artist, album)
            focus == FocusAlbum && album != null -> VoiceRequest.PlayRef(ref("release:${q(album)}"), album)
            focus == FocusArtist && artist != null -> radio(artist)
            else -> plain(query ?: listOfNotNull(artist, album, title).joinToString(" "))
        }

    private fun radio(artist: String) = VoiceRequest.PlayRef(ref("artist:${q(artist)}", radio = true), artist)
    private fun song(artist: String, song: String) = VoiceRequest.PlayRef(ref("+artist:${q(artist)} +title:${q(song)}"), song)
    private fun album(artist: String, album: String) = VoiceRequest.PlayRef(ref("+artist:${q(artist)} +release:${q(album)}"), album)

    /** Plain words: the server's best match (artist, album, song or station); syntax as typed. */
    private fun plain(q: String) = VoiceRequest.PlayRef(ref(q, best = !q.contains(':')), q)

    private fun q(s: String) = "\"${s.replace("\"", "")}\""

    private fun ref(query: String, radio: Boolean = false, best: Boolean = false): String =
        "/music/search?q=" + encode(query) + (if (radio) "&radio=1" else "") + (if (best) "&m=1" else "")

    /** URL-encodes the query ("+" stays meaningful to the server's search, so it's escaped). */
    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && b >= 0 || c in "-_.~") append(c) else append("%%%02X".format(b.toInt() and 0xff))
        }
    }

    private fun <T> both(a: String?, b: String?, f: (String, String) -> T): T? =
        if (a != null && b != null) f(a, b) else null
}
