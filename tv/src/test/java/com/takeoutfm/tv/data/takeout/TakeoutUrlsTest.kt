package com.takeoutfm.tv.data.takeout

import com.takeoutfm.tv.data.takeout.TakeoutUrls.Companion.absolute
import com.takeoutfm.tv.data.takeout.TakeoutUrls.Companion.tmdbImage
import org.junit.Assert.assertEquals
import org.junit.Test

class TakeoutUrlsTest {
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
