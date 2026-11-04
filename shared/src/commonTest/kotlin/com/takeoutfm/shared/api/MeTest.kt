package com.takeoutfm.shared.api

import com.takeoutfm.shared.library.TakeoutLibrary
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
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** /api/me: the signed-in user's name, saved with the session for the settings screens. */
class MeTest {
    private val server = "https://takeout.example"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val apiTokens = ApiTokens("access", "media", "refresh")

    @Test
    fun theUserIsSavedUntilSignOutOrANewSignIn() {
        val store = MemoryStore()
        TakeoutSession(store).apply {
            signIn(server, apiTokens)
            setUser("defsub")
        }
        val reloaded = TakeoutSession(store)
        assertEquals("defsub", reloaded.state.value.user)

        reloaded.updateTokens("access2", "refresh2")
        assertEquals("defsub", TakeoutSession(store).state.value.user)

        reloaded.signIn(server, apiTokens)
        assertNull(reloaded.state.value.user)
        assertNull(TakeoutSession(store).state.value.user)

        reloaded.setUser("defsub")
        reloaded.signOut()
        assertNull(reloaded.state.value.user)
        assertNull(TakeoutSession(store).state.value.user)
    }

    @Test
    fun libraryRefreshAsksWhoIsSignedIn() = runTest {
        val session = TakeoutSession(MemoryStore()).apply { signIn(server, apiTokens) }
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/me" -> respond("""{"User":"defsub"}""", headers = jsonHeaders)
                else -> respond("""{"HasRecommendMovies":false}""", headers = jsonHeaders)
            }
        }
        val library = TakeoutLibrary(TakeoutApi(session, "test", engine), session, MemoryStore())
        library.refresh()
        assertEquals("defsub", session.state.value.user)

        // A young index skips the refresh, and the user is already known.
        seen.clear()
        library.refresh(maxAgeMs = 60_000)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun aServerWithoutMeIsAskedAgainOnlyWithTheIndex() = runTest {
        val session = TakeoutSession(MemoryStore()).apply { signIn(server, apiTokens) }
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/me" -> respondError(HttpStatusCode.NotFound)
                else -> respond("""{"HasRecommendMovies":false}""", headers = jsonHeaders)
            }
        }
        val library = TakeoutLibrary(TakeoutApi(session, "test", engine), session, MemoryStore())
        library.refresh()
        assertNull(session.state.value.user)
        assertEquals(listOf("/api/me", "/api/index"), seen)

        seen.clear()
        library.refresh(maxAgeMs = 60_000)
        assertEquals(emptyList(), seen)
    }
}
