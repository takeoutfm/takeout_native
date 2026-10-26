package com.takeoutfm.mobile.voice

import com.takeoutfm.mobile.voice.VoiceRequests.ActionPrefix
import com.takeoutfm.mobile.voice.VoiceRequests.PlayFromSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRequestsTest {
    private fun ref(r: VoiceRequest?) = (r as VoiceRequest.PlayRef).ref

    @Test
    fun appActionsBecomeTheFlutterAppsSearchRefs() {
        assertEquals(
            "/music/search?q=artist%3A%22Beck%22&radio=1",
            ref(VoiceRequests.parse("${ActionPrefix}PLAY_ARTIST", mapOf("artist" to "Beck"))),
        )
        assertEquals(
            "/music/search?q=%2Bartist%3A%22Beck%22%20%2Btitle%3A%22Loser%22",
            ref(VoiceRequests.parse("${ActionPrefix}PLAY_ARTIST_SONG", mapOf("artist" to "Beck", "song" to "Loser"))),
        )
        assertEquals(VoiceRequest.PlayStation("BAGeL Radio"), VoiceRequests.parse("${ActionPrefix}PLAY_RADIO", mapOf("station" to "BAGeL Radio")))
        assertEquals(VoiceRequest.Pause, VoiceRequests.parse("${ActionPrefix}PLAYER_PAUSE", emptyMap()))
        assertNull(VoiceRequests.parse("${ActionPrefix}PLAY_ARTIST", emptyMap()))
        assertNull(VoiceRequests.parse("something.else", emptyMap()))
    }

    @Test
    fun playFromSearchUsesAssistantsFocusOrTheBestMatch() {
        assertEquals(VoiceRequest.Play, VoiceRequests.parse(PlayFromSearch, mapOf("query" to "")))
        assertEquals(
            "/music/search?q=artist%3A%22Beck%22&radio=1",
            ref(VoiceRequests.parse(PlayFromSearch, mapOf("query" to "beck", "android.intent.extra.focus" to "vnd.android.cursor.item/artist", "android.intent.extra.artist" to "Beck"))),
        )
        assertEquals(
            "/music/search?q=odelay&m=1",
            ref(VoiceRequests.parse(PlayFromSearch, mapOf("query" to "odelay"))),
        )
        // The server's syntax goes through as said, without best-match.
        assertEquals("/music/search?q=guitar%3Ajack", ref(VoiceRequests.parse(PlayFromSearch, mapOf("query" to "guitar:jack"))))
    }
}
