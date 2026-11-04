@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.linux

import com.takeoutfm.shared.store.SecretStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.mkdtemp
import platform.posix.system
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The C API, called as the app calls it: C callbacks, JSON in and out, a mock server. */
class BridgeTest {
    private val server = "https://takeout.example"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val saved = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    private fun tempDir(): String = memScoped {
        mkdtemp("/tmp/takeout-bridge-XXXXXX".cstr.getPointer(this))!!.toKString().also { dirs += it }
    }

    private fun start(engine: MockEngine, secrets: String? = null) {
        val store = SecretStore(secrets) { saved += it }
        Bridge.calls = LinuxCalls(LinuxCore(store, "test", tempDir(), engine))
    }

    @AfterTest
    fun stop() {
        Bridge.calls = null
        dirs.forEach { system("rm -rf '$it'") } // the open database too: unlinking it is fine
    }

    private fun call(name: String, args: String? = null): Answer = runBlocking {
        val answers = Channel<Answer>(1)
        val ref = StableRef.create(answers)
        try {
            memScoped { takeoutCall(name.cstr.ptr, args?.cstr?.ptr, onResult, ref.asCPointer()) }
            withTimeout(5_000) { answers.receive() }
        } finally {
            ref.dispose()
        }
    }

    @Test
    fun callsAnswerWithJsonOrAnError() {
        start(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/radio" -> respond("""{"Stream":[{"ID":3,"Name":"KEXP","Description":"Seattle"}]}""", headers = jsonHeaders)
                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
            secrets = """{"takeout_session.endpoint":"$server","takeout_session.access":"a",""" +
                """"takeout_session.media":"m","takeout_session.refresh":"r"}""",
        )
        assertEquals(Answer("1", null), call("version"))

        val radio = call("radio")
        assertNull(radio.error)
        assertEquals(
            """[{"kind":"Stream","stations":[{"id":"st-3","name":"KEXP","description":"Seattle","imageUri":""}]}]""",
            radio.result,
        )

        val missing = call("albumDetails", """{"albumId":"al-9"}""")
        assertNull(missing.result)
        val error = Json.parseToJsonElement(missing.error!!).jsonObject
        assertEquals("http", error["kind"]!!.jsonPrimitive.content)
        assertEquals(404, error["status"]!!.jsonPrimitive.int)

        assertEquals(Answer(null, """{"kind":"unknown","message":"nope","status":0}"""), call("nope"))
        assertTrue(call("albumDetails").error!!.contains(""""kind":"argument""""))
        assertEquals(Answer("\"Bearer m\"", null), call("mediaAuthorization", """{"url":"$server/api/tracks/1/location"}"""))
    }

    @Test
    fun deviceCodeSignInSavesTheSessionInTheSecrets() {
        var checks = 0
        start(
            MockEngine { request ->
                when {
                    request.url.encodedPath != "/api/code" -> respondError(HttpStatusCode.NotFound)
                    request.method == HttpMethod.Get -> respond("""{"Code":"AB12CD","AccessToken":"code-token"}""", headers = jsonHeaders)
                    ++checks < 2 -> respondError(HttpStatusCode.Unauthorized) // not approved yet
                    else -> respond("""{"AccessToken":"a","MediaToken":"m","RefreshToken":"r"}""", headers = jsonHeaders)
                }
            },
        )
        val session = Watch("session")
        try {
            assertEquals("""{"signedIn":false,"endpoint":"","user":null}""", session.next())
            assertEquals(Answer("""{"code":"AB12CD","link":"$server/link"}""", null), call("requestCode", """{"host":"takeout.example"}"""))
            assertEquals(Answer("true", null), call("awaitCode", """{"intervalMs":1,"tries":5}"""))
            assertEquals(2, checks)
            assertEquals("""{"signedIn":true,"endpoint":"$server","user":null}""", session.next())
            val secrets = Json.parseToJsonElement(saved.last()).jsonObject
            assertEquals("m", secrets["takeout_session.media"]!!.jsonPrimitive.content)
        } finally {
            session.stop()
        }
    }

    @Test
    fun watchesFollowChangesUntilStopped() {
        start(MockEngine { respondError(HttpStatusCode.NotFound) })
        val settings = Watch("settings")
        try {
            assertTrue(settings.next().contains(""""theme":"System""""))
            assertEquals(Answer("null", null), call("setSettings", """{"theme":"Dark"}"""))
            assertTrue(settings.next().contains(""""theme":"Dark""""))
        } finally {
            settings.stop()
        }
        call("setSettings", """{"theme":"Light"}""")
        assertTrue(settings.values.tryReceive().isFailure) // nothing after unwatch
        assertNull(memScoped { takeoutWatch("nope".cstr.ptr, onValue, null) })
    }

    @Test
    fun secretStoresKeepTheirOwnKeysInOneObject() {
        val store = SecretStore("""{"a.token":"1"}""") { saved += it }
        val a = store.named("a")
        val b = store.named("b")
        assertEquals("1", a.getString("token"))
        assertNull(b.getString("token"))
        b.putString("token", "2")
        a.remove("token")
        assertEquals("""{"b.token":"2"}""", saved.last())
        assertEquals("2", SecretStore(saved.last()) {}.named("b").getString("token"))
    }

    /** A takeout_watch whose values arrive in [values]. */
    private class Watch(name: String) {
        val values = Channel<String>(Channel.UNLIMITED)
        private val ref = StableRef.create(values)
        private val handle: COpaquePointer =
            assertNotNull(memScoped { takeoutWatch(name.cstr.ptr, onValue, ref.asCPointer()) })

        fun next(): String = runBlocking { withTimeout(5_000) { values.receive() } }

        fun stop() {
            takeoutUnwatch(handle)
            ref.dispose()
        }
    }
}

/** What a takeout_call callback was given: the result's JSON or the error's. */
private data class Answer(val result: String?, val error: String?)

private val onResult = staticCFunction { ctx: COpaquePointer?, result: CPointer<ByteVar>?, error: CPointer<ByteVar>? ->
    ctx!!.asStableRef<Channel<Answer>>().get().trySend(Answer(result?.toKString(), error?.toKString()))
    Unit
}

private val onValue = staticCFunction { ctx: COpaquePointer?, json: CPointer<ByteVar>? ->
    ctx?.asStableRef<Channel<String>>()?.get()?.trySend(json!!.toKString())
    Unit
}
