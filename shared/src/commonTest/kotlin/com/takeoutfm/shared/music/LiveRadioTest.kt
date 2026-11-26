package com.takeoutfm.shared.music

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.model.ApiTokens
import com.takeoutfm.shared.store.MemoryStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

class LiveRadioTest {
    private val server = "https://takeout.example"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // The server's now: 10:01:00, a minute into "So What" (10:00–10:09).
    private val t0 = Instant.parse("2026-11-26T10:00:00Z").toEpochMilliseconds()
    private val serverNow = t0 + 60_000

    private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms).toString()

    private fun track(start: Long, end: Long, title: String, id: Int, show: String = "") =
        """{"Start":"${at(start)}","End":"${at(end)}","Title":"$title","Artist":"Miles Davis",
            "Release":"Kind of Blue","Image":"/img/$id","Location":"/api/tracks/u$id/location",
            "ETag":"e$id","ID":$id${if (show.isEmpty()) "" else ""","Show":"$show""""}}"""

    private val jazz = """{"ID":41,"Name":"Jazz FM","Image":"/img/jazz","Tracks":[
        ${track(t0 - 300_000, t0, "Freddie Freeloader", 1)},
        ${track(t0, t0 + 540_000, "So What", 2)},
        ${track(t0 + 540_000, t0 + 900_000, "Blue in Green", 3, show = "Jazz at Night")}],
        "Shows":[{"Name":"Jazz at Night","Image":"/img/show"}]}"""

    private val seen = mutableListOf<String>()

    private fun repository(): TakeoutMusicRepository {
        val session = TakeoutSession(store = null).apply { signIn(server, ApiTokens("access", "media", "refresh")) }
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            seen += request.url.encodedPathAndQuery
            when (path) {
                "/api/radio" -> respond(
                    """{"Live":[{"ID":41,"Name":"Jazz FM","Type":"live","Creator":"Takeout"}],
                        "Stream":[{"ID":7,"Name":"KEXP","Type":"stream","Image":"https://kexp/img"}]}""",
                    headers = jsonHeaders,
                )
                "/api/radio/schedule" -> respond("""{"Time":$serverNow,"Stations":[$jazz]}""", headers = jsonHeaders)
                "/api/radio/41/schedule" -> respond("""{"Time":$serverNow,"Stations":[$jazz]}""", headers = jsonHeaders)
                "/api/radio/7/schedule" -> respondError(HttpStatusCode.NotFound)
                "/api/radio/7/playlist" -> respond(
                    """{"playlist":{"title":"KEXP","location":"/api/radio/7",
                        "track":[{"title":"KEXP","location":["https://kexp/stream"]}]}}""",
                    headers = jsonHeaders,
                )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val api = TakeoutApi(session, "test", engine)
        return TakeoutMusicRepository(api, TakeoutLibrary(api, session, MemoryStore()), TakeoutUrls(session))
    }

    @Test
    fun stationsAreLiveThenStreams() = runTest {
        val groups = repository().radio()
        assertEquals(listOf(StationKind.Live, StationKind.Stream), groups.map { it.kind })
        assertEquals(listOf(true, false), groups.flatMap { it.stations }.map { it.live })
        assertEquals("st-41", groups[0].stations[0].id)
    }

    @Test
    fun tuningInPlaysTheTrackOnNowFromWhereItIs() = runTest {
        val queue = repository().stationQueue("st-41")
        assertEquals(listOf("So What", "Blue in Green"), queue.items.map { it.title })
        assertEquals(0, queue.startIndex)
        // a minute in, give or take the test's own time
        assertTrue(abs(queue.startPositionMs - 60_000) < 5_000, "${queue.startPositionMs}")
        assertEquals("/api/radio/41", queue.location)
        val slot = queue.items[1].live!!
        assertEquals("st-41", slot.stationId)
        assertEquals("Jazz FM · Jazz at Night", slot.label)
        assertEquals(t0 + 540_000, slot.startMs)
        assertEquals("e2", queue.items[0].etag)
        assertEquals("$server/api/tracks/u2/location", queue.items[0].uri)
        assertTrue(seen.single().startsWith("/api/radio/41/schedule?"))
    }

    @Test
    fun aStreamPlaysItsPlaylist() = runTest {
        val queue = repository().stationQueue("st-7")
        assertEquals(listOf("https://kexp/stream"), queue.items.map { it.uri })
        assertNull(queue.items[0].live)
    }

    @Test
    fun moreIsFetchedFromTheLastQueuedTracksEnd() = runTest {
        val music = repository()
        val queue = music.stationQueue("st-41")
        seen.clear()
        // only "So What" queued: "Blue in Green" comes after it; nothing before
        val more = music.liveMore(queue.items.take(1))
        assertEquals(listOf("Blue in Green"), more.map { it.title })
        val start = at(t0 + 540_000).replace(":", "%3A")
        assertTrue(seen.single().startsWith("/api/radio/41/schedule?start=$start"), seen.single())
    }

    @Test
    fun onNowForTheRadioScreen() = runTest {
        val now = repository().radioNow()
        val on = now.stations.getValue("st-41")
        assertEquals("So What", on.track.title)
        assertEquals("Miles Davis – So What", on.line)
        assertEquals("Blue in Green", on.next?.title)
        assertNull(on.show)
        assertTrue(now.refreshAtMs > 0)
    }

    @Test
    fun replayingALiveStationTunesInAgain() = runTest {
        val music = repository()
        val played = MusicHistory.recorded(emptyList(), music.stationQueue("st-41"), 0).first.single()
        assertEquals("st-41", played.liveStationId)
        seen.clear()
        assertEquals("So What", music.replayQueue(played).items.first().title)
        assertEquals(1, seen.size)
    }

    // ---- The player's rules ----

    private val slot = LiveSlot("st-41", "Jazz FM", startMs = 100_000, endMs = 400_000, skewMs = 1_000)

    @Test
    fun aTrackOnTimePlaysAsItIs() {
        // device 99 s + 1 s skew = the track's start
        assertEquals(LiveStart.AsIs, LiveRadio.check(slot, 0, 99_000, behindLive = false))
        assertEquals(LiveStart.AsIs, LiveRadio.check(slot, 0, 103_000, behindLive = false))
    }

    @Test
    fun aTrackOffTheScheduleSeeksToTheStation() {
        assertEquals(LiveStart.SeekTo(20_000), LiveRadio.check(slot, 0, 119_000, behindLive = false))
        assertEquals(LiveStart.SeekTo(30_000), LiveRadio.check(slot, 10_000, 129_000, behindLive = false))
    }

    @Test
    fun aStaleTrackJumpsToLive() {
        assertEquals(LiveStart.JumpToLive, LiveRadio.check(slot, 0, 500_000, behindLive = false))
        // behind live plays on, until the schedule has gone
        assertEquals(LiveStart.AsIs, LiveRadio.check(slot, 0, 500_000, behindLive = true))
        assertEquals(LiveStart.JumpToLive, LiveRadio.check(slot, 0, 100_000 + LiveRadio.StaleMs + 1, behindLive = true))
    }

    @Test
    fun aShortPauseResumesBehindLive() {
        assertTrue(LiveRadio.keepBehind(slot, 0, behindMs = 4 * 60_000L))
        // longer than 5 minutes but within what the paused track had left
        assertTrue(LiveRadio.keepBehind(slot, 0, behindMs = 290_000L))
        assertTrue(!LiveRadio.keepBehind(slot, 200_000, behindMs = 6 * 60_000L))
    }

    @Test
    fun onlyLaterTracksAreAdded() {
        val schedule = RadioSchedule(
            "st-41", "Jazz FM", "",
            listOf(
                RadioTrack(0, 10, "A", "", "", "", "a", "ea", "t-1"),
                RadioTrack(10, 20, "B", "", "", "", "b", "eb", "t-2"),
                RadioTrack(20, 30, "C", "", "", "", "c", "ec", "t-3"),
            ),
        )
        val items = schedule.tracks.map(schedule::queueItem)
        assertEquals(listOf("C"), LiveRadio.after(items.take(2), items).map { it.title })
        assertEquals(1, onNowIndex(schedule.tracks, 15))
        assertEquals(-1, onNowIndex(schedule.tracks, 30))
    }
}
