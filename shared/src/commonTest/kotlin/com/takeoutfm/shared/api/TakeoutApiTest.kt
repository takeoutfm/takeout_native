package com.takeoutfm.shared.api

import com.takeoutfm.shared.model.ApiTokens
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
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class TakeoutApiTest {
    private val server = "https://takeout.example"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun session() = TakeoutSession(store = null).apply {
        signIn(server, ApiTokens("access1", "media", "refresh1"))
    }

    @Test
    fun expiredAccessTokenIsRefreshedAndTheRequestRetried() = runTest {
        val session = session()
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            seen += "${request.url.encodedPath} $auth"
            when {
                request.url.encodedPath == "/api/token" -> respond(
                    """{"AccessToken":"access2","RefreshToken":"refresh2"}""",
                    headers = jsonHeaders,
                )
                auth == "Bearer access1" -> respondError(HttpStatusCode.Unauthorized)
                else -> respond("""{"Offsets":[]}""", headers = jsonHeaders)
            }
        }
        TakeoutApi(session, "test", engine).progress()
        assertEquals(
            listOf(
                "/api/progress Bearer access1",
                "/api/token Bearer refresh1",
                "/api/progress Bearer access2",
            ),
            seen,
        )
        assertEquals("access2", session.tokens?.accessToken)
        assertEquals("media", session.tokens?.mediaToken)
    }

    @Test
    fun rejectedRefreshTokenSignsOut() = runTest {
        val session = session()
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        assertFailsWith<HttpException> { TakeoutApi(session, "test", engine).index() }
        assertFalse(session.state.value.isLoggedIn)
        assertEquals(server, session.endpoint) // kept to prefill sign-in
    }

    @Test
    fun favoritesUsePutAndDeleteAndQueriesAreEncoded() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPathAndQuery}"
            respond("{}", headers = jsonHeaders)
        }
        val api = TakeoutApi(session(), "test", engine)
        api.setFavorite("movies/7", true)
        api.setFavorite("movies/7", false)
        api.search("artist:Beck & co")
        assertEquals(
            listOf(
                "${HttpMethod.Put.value} /api/favorite/movies/7",
                "${HttpMethod.Delete.value} /api/favorite/movies/7",
                "GET /api/search?q=artist%3ABeck%20%26%20co",
            ),
            seen,
        )
    }

    @Test
    fun endpointsAreNormalized() {
        assertEquals("https://takeout.fm", TakeoutApi.normalizeEndpoint(" takeout.fm/ "))
        assertEquals("http://nas:3000", TakeoutApi.normalizeEndpoint("http://nas:3000"))
    }
}
