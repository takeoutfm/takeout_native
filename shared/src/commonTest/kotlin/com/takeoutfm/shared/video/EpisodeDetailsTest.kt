package com.takeoutfm.shared.video

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.model.ApiTokens
import com.takeoutfm.shared.store.MemoryStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class EpisodeDetailsTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val episodeJson = """
        {"Series":{"ID":4,"Name":"Breaking Bad","Rating":"TV-MA"},
         "Episode":{"ID":12,"Name":"Cat's in the Bag...","Season":1,"Episode":2,"Runtime":48,
                    "Date":"2008-01-27T00:00:00Z","ETag":"et12","VoteAverage":8.2,"VoteCount":30},
         "Location":"/api/tv/episodes/u12/location",
         "Cast":[{"PEID":7,"Character":"Krazy-8","Person":{"PEID":7,"Name":"Max Arciniega"}}],
         "Directing":[{"PEID":9,"Name":"Adam Bernstein"}],
         "Writing":[{"PEID":10,"Name":"Vince Gilligan"}]}
    """.trimIndent()

    private val seriesJson = """
        {"Series":{"ID":4,"Name":"Breaking Bad"},
         "Episodes":[{"ID":13,"Season":1,"Episode":3,"Name":"...And the Bag's in the River"},
                     {"ID":11,"Season":1,"Episode":1,"Name":"Pilot"},
                     {"ID":12,"Season":1,"Episode":2,"Name":"Cat's in the Bag..."},
                     {"ID":21,"Season":2,"Episode":1,"Name":"Seven Thirty-Seven"}]}
    """.trimIndent()

    private fun repository(): TakeoutVideoRepository {
        val session = TakeoutSession(store = null).apply {
            signIn("https://takeout.example", ApiTokens("access", "media", "refresh"))
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/tv/episodes/12" -> respond(episodeJson, headers = jsonHeaders)
                "/api/tv/series/4" -> respond(seriesJson, headers = jsonHeaders)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val api = TakeoutApi(session, "test", engine)
        val progress = object : ProgressSource {
            override suspend fun latest(): Map<String, ApiOffset> = emptyMap()
            override suspend fun inProgress(days: Int): List<ApiOffset> = emptyList()
        }
        return TakeoutVideoRepository(api, session, TakeoutLibrary(api, session, MemoryStore()), TakeoutUrls(session), progress)
    }

    @Test
    fun anEpisodeHasItsCastCrewRatingAndPlaceInTheSeries() = runTest {
        val d = repository().details("e-12")
        assertEquals(VideoKind.Episode, d.kind)
        assertEquals(listOf("Adam Bernstein", "Max Arciniega"), d.cast.map { it.name })
        assertEquals("Krazy-8", d.cast.last().role)
        assertEquals(listOf("Vince Gilligan"), d.writers)
        assertEquals(8.2f, d.voteAverage)
        val e = d.episode!!
        assertEquals("s-4", e.seriesId)
        assertEquals("Breaking Bad", e.seriesTitle)
        assertEquals("S1E2", e.label)
        assertEquals("2008-01-27", e.airDate)
        // The season's episodes in order, without other seasons.
        assertEquals(listOf("e-11", "e-12", "e-13"), e.seasonEpisodes.map { it.id })
        assertEquals("e-11", d.previousEpisodeId)
        assertEquals("e-13", d.nextEpisodeId)
    }
}
