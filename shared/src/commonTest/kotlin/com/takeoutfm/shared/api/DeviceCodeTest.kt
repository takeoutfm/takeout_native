package com.takeoutfm.shared.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCodeTest {
    private val server = "https://takeout.example"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val checks = mutableListOf<Long>()

    /** An api on the test's virtual clock; [check] answers the POSTs. */
    private fun TestScope.api(
        code: String = """{"Code":"AB12CD","AccessToken":"t","ExpiresIn":60,"Interval":10}""",
        check: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = TakeoutApi(
        TakeoutSession(store = null),
        "test",
        MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond(code, headers = jsonHeaders)
            } else {
                checks += currentTime
                assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
                assertEquals("""{"Code":"AB12CD"}""", (request.body as TextContent).text)
                check(request)
            }
        },
    ).also { it.now = { currentTime } }

    @Test
    fun checksAtTheServersIntervalUntilApproved() = runTest {
        val api = api { if (checks.size < 3) respondError(HttpStatusCode.Forbidden) else respond(
            """{"AccessToken":"a","MediaToken":"m","RefreshToken":"r"}""", headers = jsonHeaders,
        ) }
        val code = api.requestCode(server)
        assertEquals("$server/link", code.link)
        assertEquals(60, code.secondsLeft(currentTime))
        assertEquals("a", api.awaitCode(code)?.accessToken)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), checks)
    }

    @Test
    fun givesUpWhenTheCodeExpires() = runTest {
        val api = api { respondError(HttpStatusCode.Forbidden) }
        val code = api.requestCode(server)
        assertNull(api.awaitCode(code))
        assertEquals(5, checks.size) // 10..50 s; at 60 s it has expired
        assertEquals(60_000L, currentTime)
        assertEquals(0, code.secondsLeft(currentTime))
    }

    @Test
    fun stopsWhenTheServerNoLongerKnowsTheCode() = runTest {
        val api = api { respondError(HttpStatusCode.Unauthorized) }
        assertNull(api.awaitCode(api.requestCode(server)))
        assertEquals(listOf(10_000L), checks)
    }

    @Test
    fun slowsDownWhenTooMany() = runTest {
        val api = api { if (checks.size == 1) respondError(HttpStatusCode.TooManyRequests) else respondError(HttpStatusCode.Unauthorized) }
        api.awaitCode(api.requestCode(server))
        assertEquals(listOf(10_000L, 25_000L), checks)
    }

    @Test
    fun olderServersGetTheDefaults() = runTest {
        val api = api(code = """{"Code":"AB12CD","AccessToken":"t"}""") { respondError(HttpStatusCode.Forbidden) }
        val code = api.requestCode(server)
        assertEquals(300, code.expiresIn)
        assertEquals(5_000L, code.intervalMs)
        assertEquals("5:00", DeviceCode.countdown(code.secondsLeft(currentTime)))
        assertEquals("0:09", DeviceCode.countdown(9))
    }
}
