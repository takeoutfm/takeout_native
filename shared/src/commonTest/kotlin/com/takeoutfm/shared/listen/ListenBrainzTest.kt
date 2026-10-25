package com.takeoutfm.shared.listen

import com.takeoutfm.shared.store.MemoryStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class ListenBrainzTest {
    @Test
    fun validTokenIsSavedAndUsedForListens() = runTest {
        val store = MemoryStore()
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals("Token abc", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/1/validate-token" -> respond("""{"valid":true,"user_name":"defsub"}""")
                else -> {
                    bodies += (request.body as TextContent).text
                    respond("""{"status":"ok"}""")
                }
            }
        }
        val account = ListenBrainzAccount(store, engine)
        assertEquals("defsub", account.connect("  abc ")?.name)
        assertEquals("abc", store.getString("token"))
        account.submitListens(listOf(ListenTrack("Beck", "Loser") to 1_700_000_000L))
        assertTrue(bodies.single().contains(""""listen_type":"single""""))
        assertTrue(bodies.single().contains(""""listened_at":1700000000"""))
    }

    @Test
    fun rejectedTokenIsNotSaved() = runTest {
        val store = MemoryStore()
        val engine = MockEngine { respond("""{"valid":false}""", HttpStatusCode.Unauthorized) }
        assertNull(ListenBrainzAccount(store, engine).connect("bad"))
        assertNull(store.getString("token"))
    }

    @Test
    fun revokedTokenDisconnects() = runTest {
        val store = MemoryStore().apply { putStrings(mapOf("token" to "abc", "user" to "defsub")) }
        val account = ListenBrainzAccount(store, MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertEquals("defsub", account.user.value?.name)
        assertFailsWith<IOException> { account.playingNow(ListenTrack("Beck", "Loser")) }
        assertNull(account.user.value)
        assertNull(store.getString("token"))
    }
}
