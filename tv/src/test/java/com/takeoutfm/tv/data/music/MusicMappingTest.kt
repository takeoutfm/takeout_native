package com.takeoutfm.tv.data.music

import com.takeoutfm.tv.data.music.TakeoutMusicRepository.Companion.numericId
import com.takeoutfm.tv.data.music.TakeoutMusicRepository.Companion.toQueue
import com.takeoutfm.tv.data.music.TakeoutMusicRepository.Companion.year
import com.takeoutfm.tv.data.takeout.TakeoutUrls.Companion.releaseCoverPath
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicMappingTest {
    private val server = "https://takeout.example"
    private val absolute = { path: String -> if (path.isEmpty()) "" else server + path }

    @Test
    fun spiffPlaylistBecomesAQueue() {
        val json = """
            {"playlist": {"title": "Abbey Road", "creator": "The Beatles",
              "image": "/img/mb/re/abc/front",
              "track": [
                {"creator": "The Beatles", "album": "Abbey Road", "title": "Come Together",
                 "image": "/img/mb/re/abc/front", "background": "/img/fa/ar1/b/bg.jpg",
                 "location": ["/api/tracks/u1/location"], "identifier": ["etag1"], "size": [1]},
                {"title": "No location"},
                {"creator": "The Beatles", "album": "Abbey Road", "title": "Something",
                 "location": ["/api/tracks/u2/location"]}
              ]},
             "index": 1, "position": 0, "type": "music"}
        """.trimIndent()
        val playlist = Json { ignoreUnknownKeys = true }
            .decodeFromString(ApiPlaylist.serializer(), json)

        val queue = playlist.toQueue(absolute)

        assertEquals("Abbey Road", queue.title)
        assertEquals("$server/img/mb/re/abc/front", queue.imageUri)
        assertEquals(listOf("Come Together", "Something"), queue.items.map { it.title })
        assertEquals("$server/api/tracks/u1/location", queue.items[0].uri)
        assertEquals("etag1", queue.items[0].etag)
        assertEquals("$server/img/fa/ar1/b/bg.jpg", queue.items[0].backgroundUri)
        assertEquals("", queue.items[1].backgroundUri)
        assertEquals("", queue.items[1].etag)
        assertEquals(1, queue.startIndex)
    }

    @Test
    fun startIndexStaysInRange() {
        val playlist = ApiPlaylist(
            playlist = ApiSpiff(
                tracks = listOf(ApiSpiffEntry(title = "One", location = listOf("/a")))
            ),
            index = 5,
        )
        assertEquals(0, playlist.toQueue(absolute).startIndex)
        assertEquals(0, ApiPlaylist().toQueue(absolute).startIndex)
    }

    @Test
    fun coversFollowTheServersRule() {
        assertEquals("/img/mb/re/re1/front", releaseCoverPath("re1", "rg1", true, true, "", false))
        assertEquals("/img/mb/rg/rg1/front", releaseCoverPath("re1", "rg1", true, true, "", true))
        assertEquals("/img/mb/re/re1", releaseCoverPath("re1", "rg1", true, false, "123", false))
        assertEquals("", releaseCoverPath("re1", "rg1", false, false, "", false))
    }

    @Test
    fun yearsAndIds() {
        assertEquals("1969", year("1969-09-26T00:00:00Z"))
        assertEquals("", year("0001-01-01T00:00:00Z"))
        assertEquals("", year(""))
        assertEquals(42, numericId("al-42", "al-"))
    }
}
