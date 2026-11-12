package com.takeoutfm.shared.guide

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.model.ApiTokens
import com.takeoutfm.shared.store.MemoryStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class GuideTest {
    private val server = "https://takeout.example"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun session() = TakeoutSession(store = null).apply {
        signIn(server, ApiTokens("access", "media", "refresh"))
    }

    private val guideJson = """
        {"Time": 1762891800000, "Start": "2025-11-11T20:10:00Z", "End": "2025-11-11T23:10:00Z",
         "Channels": [{"Name": "Movies", "Programs": [
           {"Kind": "episode", "Start": "2025-11-11T21:00:00Z", "End": "2025-11-11T21:30:00Z",
            "Title": "Show", "Subtitle": "Pilot", "Location": "/api/tv/episodes/u2/location",
            "TargetID": 7, "TVID": 99, "Season": 1, "Episode": 1, "Unknown": true},
           {"Kind": "movie", "Start": "2025-11-11T19:00:00Z", "End": "2025-11-11T20:55:00Z",
            "Title": "Film", "Image": "/img/tm/w342/a.jpg", "ETag": "x",
            "Location": "/api/movies/u1/location", "TargetID": 42, "TMID": 11},
           {"Kind": "offair", "Start": "2025-11-11T22:00:00Z", "End": "2025-11-11T23:30:00Z"}
         ]}]}
    """.trimIndent()

    private fun at(s: String) = Instant.parse(s)

    private fun fixedClock(t: Instant) = object : Clock {
        override fun now() = t
    }

    @Test
    fun guideIsMappedAndOrderedWithTheServersClock() = runTest {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPathAndQuery
            respond(guideJson, headers = jsonHeaders)
        }
        val session = session()
        // the device runs 90s slow
        val device = at("2025-11-11T20:08:30Z")
        val repo = TakeoutGuideRepository(TakeoutApi(session, "test", engine), TakeoutUrls(session), fixedClock(device))
        val guide = repo.guide(start = at("2025-11-11T20:10:00Z"))
        assertEquals("/api/guide?start=2025-11-11T20%3A10%3A00Z", path)
        assertEquals(90.seconds, guide.skew)
        assertEquals(at("2025-11-11T20:10:00Z"), guide.now(fixedClock(device)))

        val programs = guide.channel("Movies")!!.programs
        assertEquals(listOf("Film", "Show", ""), programs.map { it.title })
        val movie = programs[0]
        assertEquals(ProgramKind.Movie, movie.kind)
        assertEquals("m-42", movie.videoId)
        assertEquals("$server/api/movies/u1/location", movie.videoUri)
        assertEquals("$server/img/tm/w342/a.jpg", movie.imageUri)
        assertEquals(115.minutes, movie.duration)
        assertEquals("e-7", programs[1].videoId)
        assertEquals("Pilot", programs[1].subtitle)
        assertEquals(ProgramKind.OffAir, programs[2].kind)
        assertNull(programs[2].videoId)
        assertEquals("", programs[2].videoUri)
    }

    @Test
    fun breakTracksAreKeptApartFromPrograms() {
        val repo = TakeoutGuideRepository(TakeoutApi(session(), "test"), TakeoutUrls(session()))
        val json = """
            {"Time": 1762891800000, "Channels": [{"Name": "Movies", "Programs": [
              {"Kind": "movie", "Start": "2025-11-11T20:00:00Z", "End": "2025-11-11T21:52:00Z",
               "Title": "Film", "Location": "/api/movies/u1/location", "TargetID": 1},
              {"Kind": "track", "Start": "2025-11-11T21:52:00Z", "End": "2025-11-11T21:56:00Z",
               "Title": "So What", "Subtitle": "Miles Davis", "Image": "/img/cover.jpg",
               "Location": "/api/tracks/t1/location", "TargetID": 9},
              {"Kind": "track", "Start": "2025-11-11T21:56:00Z", "End": "2025-11-11T22:00:00Z",
               "Title": "Blue in Green", "Subtitle": "Miles Davis",
               "Location": "/api/tracks/t2/location", "TargetID": 10},
              {"Kind": "movie", "Start": "2025-11-11T22:00:00Z", "End": "2025-11-11T23:40:00Z",
               "Title": "Next", "Location": "/api/movies/u2/location", "TargetID": 2}]}]}
        """.trimIndent()
        val view = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(com.takeoutfm.shared.model.ApiGuideView.serializer(), json)
        val channel = repo.toGuide(view, at("2025-11-11T20:10:00Z")).channels.single()
        assertEquals(listOf("Film", "Next"), channel.programs.map { it.title })
        assertEquals(listOf("So What", "Blue in Green"), channel.breaks.map { it.title })
        val track = channel.breaks.first()
        assertEquals(ProgramKind.Track, track.kind)
        assertEquals("Miles Davis", track.subtitle)
        assertEquals("$server/api/tracks/t1/location", track.videoUri)
        assertNull(track.videoId)

        // in the break: no program, but music
        assertNull(onNow(channel, at("2025-11-11T21:57:00Z")).program)
        val (playing, offset) = breakAt(channel, at("2025-11-11T21:57:00Z"))!!
        assertEquals("Blue in Green", playing.title)
        assertEquals(1.minutes, offset)
        assertNull(breakAt(channel, at("2025-11-11T21:00:00Z")))

        // the two tracks are one 8 minute break: shown when breaks that long are
        assertEquals(
            listOf(MusicBlock(at("2025-11-11T21:52:00Z"), at("2025-11-11T22:00:00Z"))),
            musicBlocks(channel, 8.minutes),
        )
        assertEquals(emptyList<MusicBlock>(), musicBlocks(channel, 15.minutes))
    }

    @Test
    fun aReservedSlotIsNamedAndNotPlayable() {
        val repo = TakeoutGuideRepository(TakeoutApi(session(), "test"), TakeoutUrls(session()))
        val json = """
            {"Time": 1762891800000, "Channels": [{"Name": "Good Movies", "Programs": [
              {"Kind": "reserved", "Block": "New on Friday", "Title": "New on Friday",
               "Subtitle": "The latest movies", "Start": "2025-11-14T04:00:00Z",
               "End": "2025-11-14T08:00:00Z"}]}]}
        """.trimIndent()
        val view = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(com.takeoutfm.shared.model.ApiGuideView.serializer(), json)
        val slot = repo.toGuide(view, at("2025-11-11T20:10:00Z")).channels.single().programs.single()
        assertEquals(ProgramKind.Reserved, slot.kind)
        assertEquals("New on Friday", slot.title)
        assertEquals("New on Friday", slot.block)
        assertEquals("The latest movies", slot.subtitle)
        assertEquals("", slot.videoUri)
        assertNull(slot.videoId)
    }

    @Test
    fun onNowFindsTheProgramOffsetAndNext() {
        val repo = TakeoutGuideRepository(TakeoutApi(session(), "test"), TakeoutUrls(session()))
        val view = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(com.takeoutfm.shared.model.ApiGuideView.serializer(), guideJson)
        val channel = repo.toGuide(view, at("2025-11-11T20:10:00Z")).channels.single()

        val during = onNow(channel, at("2025-11-11T20:10:00Z"))
        assertEquals("Film", during.program?.title)
        assertEquals(70.minutes, during.offset)
        assertEquals("Show", during.next?.title)

        // a break: nothing on, the next program known
        val gap = onNow(channel, at("2025-11-11T20:57:00Z"))
        assertNull(gap.program)
        assertEquals(0.seconds, gap.offset)
        assertEquals("Show", gap.next?.title)

        // the end is exclusive
        assertEquals("Show", onNow(channel, at("2025-11-11T21:00:00Z")).program?.title)
        val after = onNow(channel, at("2025-11-12T01:00:00Z"))
        assertNull(after.program)
        assertNull(after.next)
    }

    @Test
    fun hasGuideIsOnlyTrueWhenTheServerSaysSo() = runTest {
        suspend fun hasGuide(index: String): Boolean {
            val session = session()
            val engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/index" -> respond(index, headers = jsonHeaders)
                    else -> respond("{}", headers = jsonHeaders)
                }
            }
            val library = TakeoutLibrary(TakeoutApi(session, "test", engine), session, MemoryStore())
            library.refresh()
            return library.index.value!!.hasGuide
        }
        assertFalse(hasGuide("""{"HasRecommendMovies": false}"""))
        assertTrue(hasGuide("""{"HasRecommendMovies": false, "HasGuide": true}"""))
    }

    private fun program(title: String, start: String, end: String) = GuideProgram(
        kind = ProgramKind.Movie, block = "", start = at(start), end = at(end), title = title,
        subtitle = "", rating = "", imageUri = "", backdropUri = "", videoUri = "", etag = "",
        videoId = null,
    )

    private val a = program("A", "2025-11-11T20:00:00Z", "2025-11-11T21:30:00Z")
    private val b = program("B", "2025-11-11T21:35:00Z", "2025-11-11T23:00:00Z")
    private val c = program("C", "2025-11-11T23:00:00Z", "2025-11-12T00:40:00Z")

    @Test
    fun mergeJoinsWindowsWithoutRepeats() {
        val first = Guide(
            listOf(GuideChannel("X", listOf(a, b)), GuideChannel("Y", listOf(a))),
            at("2025-11-11T20:00:00Z"), at("2025-11-11T22:00:00Z"), 0.seconds,
        )
        // B spans the edge, so it's in both; a seasonal channel Z starts in the second window
        val second = Guide(
            listOf(GuideChannel("X", listOf(b, c)), GuideChannel("Z", listOf(c))),
            at("2025-11-11T22:00:00Z"), at("2025-11-12T00:00:00Z"), 5.seconds,
        )
        val merged = first.merge(second)
        assertEquals(listOf("X", "Y", "Z"), merged.channels.map { it.name })
        assertEquals(listOf("A", "B", "C"), merged.channel("X")!!.programs.map { it.title })
        assertEquals(at("2025-11-11T20:00:00Z"), merged.start)
        assertEquals(at("2025-11-12T00:00:00Z"), merged.end)
        assertEquals(5.seconds, merged.skew)
    }

    @Test
    fun gridFocusMovesByProgramAndKeepsToTheFuture() {
        val x = GuideChannel("X", listOf(a, b, c))
        assertEquals("A", focusAt(x, at("2025-11-11T21:00:00Z"))?.title)
        // in the break between A and B: the next program
        assertEquals("B", focusAt(x, at("2025-11-11T21:32:00Z"))?.title)
        // past everything: the last program
        assertEquals("C", focusAt(x, at("2025-11-12T03:00:00Z"))?.title)
        assertNull(focusAt(GuideChannel("Empty", emptyList()), at("2025-11-11T21:00:00Z")))

        assertEquals("B", nextProgram(x, a)?.title)
        assertNull(nextProgram(x, c))
        val now = at("2025-11-11T21:00:00Z")
        assertEquals("A", previousProgram(x, b, now)?.title)
        // A is over by 21:40, so there's nothing before B
        assertNull(previousProgram(x, b, at("2025-11-11T21:40:00Z")))
    }

    @Test
    fun floorToRoundsDownToTheStep() {
        assertEquals(at("2025-11-11T21:30:00Z"), at("2025-11-11T21:47:13Z").floorTo(30.minutes))
        assertEquals(at("2025-11-11T21:30:00Z"), at("2025-11-11T21:30:00Z").floorTo(30.minutes))
        assertEquals(at("2025-11-11T18:00:00Z"), at("2025-11-11T20:10:00Z").floorTo(6.hours))
    }
}
