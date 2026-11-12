package com.takeoutfm.media

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectCacheTest {
    private val location = "https://takeout.example/api/movies/u1/location"

    /** A presigned URL signed at [signed], valid for [seconds]. */
    private fun presigned(signed: String, seconds: Int) =
        "https://s3.example/bucket/movie.mkv?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=$signed" +
            "&X-Amz-Expires=$seconds&X-Amz-Signature=abc"

    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test
    fun aStreamsRedirectIsFollowedOnceAndReused() {
        var now = ms("2026-11-12T20:00:00Z")
        var asked = 0
        val target = presigned("20261112T200000Z", 900)
        val cache = RedirectCache(clock = { now }) { uri, auth ->
            assertEquals(location, uri)
            assertEquals("Bearer media", auth)
            asked++
            target
        }
        // header, index, the position to play from: one trip to Takeout
        repeat(3) { assertEquals(target, cache.target(location, "Bearer media")) }
        assertEquals(1, asked)

        // a seek 9 minutes later still uses it
        now += 9 * 60_000
        cache.target(location, "Bearer media")
        assertEquals(1, asked)

        // after 10 minutes it's asked for again, well before its 15 run out
        now += 61_000
        cache.target(location, "Bearer media")
        assertEquals(2, asked)
    }

    @Test
    fun aShortExpiryIsRespected() {
        var now = ms("2026-11-12T20:00:00Z")
        var asked = 0
        // valid 3 minutes: kept until a minute before that
        val cache = RedirectCache(clock = { now }) { _, _ -> asked++; presigned("20261112T200000Z", 180) }
        cache.target(location, "a")
        now += 119_000
        cache.target(location, "a")
        assertEquals(1, asked)
        now += 2_000
        cache.target(location, "a")
        assertEquals(2, asked)
    }

    @Test
    fun noRedirectNothingKept() {
        var asked = 0
        val cache = RedirectCache { _, _ -> asked++; null }
        assertNull(cache.target(location, "a"))
        assertNull(cache.target(location, "a"))
        assertEquals(2, asked)
    }

    @Test
    fun forgottenTargetsAreAskedForAgain() {
        var asked = 0
        val cache = RedirectCache { _, _ -> asked++; "https://other.example/file.mp4" }
        cache.target(location, "a")
        cache.forget(location)
        cache.target(location, "a")
        assertEquals(2, asked)
    }

    @Test
    fun expiryComesFromTheURL() {
        assertEquals(ms("2026-11-12T20:15:00Z"), RedirectCache.expiresAt(presigned("20261112T200000Z", 900)))
        assertNull(RedirectCache.expiresAt("https://cdn.example/file.mkv"))
        assertNull(RedirectCache.expiresAt("https://s3.example/f?X-Amz-Date=bad&X-Amz-Expires=900"))
    }
}
