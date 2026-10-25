package com.takeoutfm.shared

import com.takeoutfm.shared.TakeoutPaths.absolute
import com.takeoutfm.shared.TakeoutPaths.tmdbImage
import kotlin.test.Test
import kotlin.test.assertEquals

class TakeoutPathsTest {
    private val server = "https://takeout.example"

    @Test
    fun pathsArePrefixedWithTheServer() {
        assertEquals("$server/api/movies/1/location", absolute(server, "/api/movies/1/location"))
    }

    @Test
    fun absoluteUrlsAndEmptyAreKept() {
        assertEquals("https://cdn.example/a.mkv", absolute(server, "https://cdn.example/a.mkv"))
        assertEquals("http://cdn.example/a.mkv", absolute(server, "http://cdn.example/a.mkv"))
        assertEquals("", absolute(server, ""))
    }

    @Test
    fun tmdbImagesGoThroughTheServerProxy() {
        assertEquals("$server/img/tm/w342/abc.jpg", tmdbImage(server, "w342", "/abc.jpg"))
        assertEquals("", tmdbImage(server, "w342", ""))
    }
}
