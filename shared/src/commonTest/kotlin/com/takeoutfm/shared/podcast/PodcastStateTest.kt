package com.takeoutfm.shared.podcast

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.db.testDb
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.model.ApiTokens
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.video.ProgressSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/** Subscriptions and episode progress are shared state that edits keep current (decision 017). */
class PodcastStateTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun session() = TakeoutSession(store = null).apply {
        signIn("https://takeout.example", ApiTokens("access", "media", "refresh"))
    }

    private object NoProgress : ProgressSource {
        override suspend fun latest(): Map<String, ApiOffset> = emptyMap()
        override suspend fun inProgress(days: Int): List<ApiOffset> = emptyList()
    }

    /** A server with podcasts 1 "Alpha" (subscribed), 2 "Beta" and 3 "Gamma"; [refuse] fails subscription changes. */
    private fun podcasts(refuse: Boolean = false): TakeoutPodcastRepository {
        fun series(id: Int, title: String) =
            """{"ID":$id,"Title":"$title","Author":"","Description":"","Image":""}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/podcasts/subscribed" ->
                    respond("""{"Series":[${series(1, "Alpha")}]}""", headers = jsonHeaders)
                path == "/api/podcasts" ->
                    respond("""{"Series":[${series(1, "Alpha")},${series(2, "Beta")},${series(3, "Gamma")}]}""", headers = jsonHeaders)
                path.endsWith("/subscribed") && refuse -> respondError(HttpStatusCode.InternalServerError)
                else -> respond("{}", headers = jsonHeaders)
            }
        }
        val session = session()
        return TakeoutPodcastRepository(TakeoutApi(session, "test", engine), TakeoutUrls(session), NoProgress)
    }

    private fun TakeoutPodcastRepository.subscribed() = subscribedSeries.value?.map { it.title }

    @Test
    fun subscribingUpdatesTheSubscribedSeries() = runTest {
        val podcasts = podcasts()
        assertNull(podcasts.subscribedSeries.value)
        podcasts.series(subscribedOnly = false)  // Beta and Gamma seen, not subscribed
        assertNull(podcasts.subscribedSeries.value)
        podcasts.series(subscribedOnly = true)
        assertEquals(listOf("Alpha"), podcasts.subscribed())

        podcasts.setSubscribed("ps-3", true)
        podcasts.setSubscribed("ps-2", true)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), podcasts.subscribed())

        podcasts.setSubscribed("ps-1", false)
        assertEquals(listOf("Beta", "Gamma"), podcasts.subscribed())
    }

    @Test
    fun aRefusedSubscriptionIsUndone() = runTest {
        val podcasts = podcasts(refuse = true)
        podcasts.series(subscribedOnly = false)
        podcasts.series(subscribedOnly = true)
        assertFailsWith<Exception> { podcasts.setSubscribed("ps-2", true) }
        assertFailsWith<Exception> { podcasts.setSubscribed("ps-1", false) }
        assertEquals(listOf("Alpha"), podcasts.subscribed())
    }

    @Test
    fun aLoadAnsweredBeforeAnUnsubscribeDoesNotUndoIt() = runTest {
        // Back pressed right after Unsubscribe: the grid's load reaches the server first, and
        // its answer (still subscribed) arrives after the unsubscribe has finished.
        val answer = CompletableDeferred<Unit>()
        var holdLoads = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/api/podcasts/subscribed") {
                if (holdLoads) answer.await()
                respond(
                    """{"Series":[{"ID":1,"Title":"Alpha","Author":"","Description":"","Image":""},{"ID":2,"Title":"Beta","Author":"","Description":"","Image":""}]}""",
                    headers = jsonHeaders,
                )
            } else {
                respond("{}", headers = jsonHeaders)
            }
        }
        val session = session()
        val podcasts = TakeoutPodcastRepository(TakeoutApi(session, "test", engine), TakeoutUrls(session), NoProgress)
        podcasts.series(subscribedOnly = true)
        assertEquals(listOf("Alpha", "Beta"), podcasts.subscribed())

        holdLoads = true
        val load = async { podcasts.series(subscribedOnly = true) }
        testScheduler.runCurrent()  // the load is waiting on the server
        podcasts.setSubscribed("ps-2", false)
        answer.complete(Unit)
        load.await()
        assertEquals(listOf("Alpha"), podcasts.subscribed())
    }

    @Test
    fun progressOffsetsFollowReportsAndTheServer() = runTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get && request.url.encodedPath == "/api/progress") {
                respond(
                    """{"Offsets":[{"ETag":"e2","Offset":600,"Duration":1800,"Date":"2026-11-01T10:00:00Z"}]}""",
                    headers = jsonHeaders,
                )
            } else {
                respond("{}", headers = jsonHeaders)
            }
        }
        val session = session()
        val progress = TakeoutProgress(TakeoutApi(session, "test", engine), session, testDb())
        assertEquals(emptyMap(), progress.offsets.value)

        // played here (or marked played): shown without loading anything
        progress.report("e1", 1_200_000, 1_800_000)
        assertEquals(1200, progress.offsets.value["e1"]?.offset)

        // another device's progress arrives with the next load
        progress.latest()
        assertEquals(setOf("e1", "e2"), progress.offsets.value.keys)
        assertEquals(TakeoutPodcastRepository.episodeProgress(progress.offsets.value["e2"]),
            EpisodeProgress.Started(fraction = 600f / 1800, minutesLeft = 20))
    }
}
