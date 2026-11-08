@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.linux

import com.takeoutfm.shared.api.Host
import com.takeoutfm.shared.api.HostRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A host written in Kotlin: it records requests and answers through the C API, as the Qt one will. */
private object FakeHost {
    class Seen(val handle: COpaquePointer, val json: String, val body: ByteArray) {
        val request = Json.parseToJsonElement(json).jsonObject
        val url get() = request.getValue("url").jsonPrimitive.content
        val method get() = request.getValue("method").jsonPrimitive.content
        fun header(name: String) = request.getValue("headers").jsonArray
            .firstOrNull { it.jsonArray[0].jsonPrimitive.content.equals(name, ignoreCase = true) }
            ?.jsonArray?.get(1)?.jsonPrimitive?.content
    }

    val seen = mutableListOf<Seen>()
    val cancels = mutableListOf<COpaquePointer>()
    var onStart: (Seen) -> Unit = {}
    var onResume: (COpaquePointer) -> Unit = {}

    fun reset() {
        seen.clear()
        cancels.clear()
        onStart = {}
        onResume = {}
    }

    fun head(handle: COpaquePointer, status: Int, headers: String = "[]") = memScoped {
        takeoutHttpHead(handle, status, headers.cstr.ptr)
    }

    /** 1 when the core asks for a pause. */
    fun data(handle: COpaquePointer, bytes: ByteArray): Int =
        bytes.usePinned { takeoutHttpData(handle, it.addressOf(0), bytes.size.toULong()) }

    fun end(handle: COpaquePointer, error: String? = null) = memScoped {
        takeoutHttpEnd(handle, error?.cstr?.ptr)
    }

    fun register() = takeoutSetHttp(
        staticCFunction { _, handle, json, body, length ->
            val bytes = if (body == null || length == 0uL) ByteArray(0) else body.reinterpret<kotlinx.cinterop.ByteVar>().readBytes(length.toInt())
            val request = Seen(handle!!, json!!.toKString(), bytes)
            seen += request
            onStart(request)
        },
        staticCFunction { _, handle -> cancels += handle!! },
        staticCFunction { _, handle -> onResume(handle!!) },
        null,
    )
}

class HostEngineTest {
    @BeforeTest
    fun setUp() {
        FakeHost.reset()
        FakeHost.register()
    }

    @AfterTest
    fun tearDown() = FakeHost.reset()

    private fun client(followRedirects: Boolean = true) = HttpClient(Host) {
        expectSuccess = false
        this.followRedirects = followRedirects
    }

    @Test
    fun getSendsTheRequestAndReadsTheResponse() = runBlocking {
        FakeHost.onStart = {
            FakeHost.head(it.handle, 200, """[["Content-Type","application/json"],["X-Two","a"],["X-Two","b"]]""")
            FakeHost.data(it.handle, """{"ok":true}""".encodeToByteArray())
            FakeHost.end(it.handle)
        }
        val response = client().get("https://takeout.example/api/me") {
            header("Authorization", "Bearer token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"ok":true}""", response.bodyAsText())
        assertEquals(listOf("a", "b"), response.headers.getAll("X-Two"))
        val seen = FakeHost.seen.single()
        assertEquals("GET", seen.method)
        assertEquals("https://takeout.example/api/me", seen.url)
        assertEquals("Bearer token", seen.header("Authorization"))
    }

    @Test
    fun postSendsItsBodyAndContentType() = runBlocking {
        FakeHost.onStart = {
            FakeHost.head(it.handle, 204)
            FakeHost.end(it.handle)
        }
        val response = client().post("https://takeout.example/api/activity") {
            contentType(ContentType.Application.Json)
            setBody("""{"a":1}""")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        val seen = FakeHost.seen.single()
        assertEquals("POST", seen.method)
        assertEquals("""{"a":1}""", seen.body.decodeToString())
        assertContains(seen.header("Content-Type").orEmpty(), "application/json")
    }

    @Test
    fun redirectsAreTheClientsToFollowNotTheHosts() = runBlocking {
        FakeHost.onStart = {
            if (it.url.endsWith("/a")) {
                FakeHost.head(it.handle, 302, """[["Location","https://storage.example/b"]]""")
            } else {
                FakeHost.head(it.handle, 200)
                FakeHost.data(it.handle, "b".encodeToByteArray())
            }
            FakeHost.end(it.handle)
        }
        // Not followed: the core sees the redirect (MediaDownloads decides about the token).
        val manual = client(followRedirects = false).get("https://takeout.example/a")
        assertEquals(HttpStatusCode.Found, manual.status)
        assertEquals("https://storage.example/b", manual.headers["Location"])
        assertEquals(1, FakeHost.seen.size)
        // Followed by Ktor's plugin, as a second request to the host.
        FakeHost.seen.clear()
        assertEquals("b", client().get("https://takeout.example/a").bodyAsText())
        assertEquals(listOf("https://takeout.example/a", "https://storage.example/b"), FakeHost.seen.map { it.url })
    }

    @Test
    fun aLargeBodyStreamsWithPauses() = runBlocking {
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val total = 8 * 1024 * 1024
        var sent = 0
        val pauses = AtomicInt(0)
        val read = kotlin.concurrent.AtomicLong(0)
        var mostAhead = 0L
        // Sends until the core asks for a pause, or to the end.
        fun send(handle: COpaquePointer) {
            while (sent < total) {
                val pause = FakeHost.data(handle, chunk)
                sent += chunk.size
                mostAhead = maxOf(mostAhead, sent - read.value)
                if (pause == 1 && sent < total) {
                    pauses.incrementAndGet()
                    return
                }
            }
            FakeHost.end(handle)
        }
        FakeHost.onStart = {
            FakeHost.head(it.handle, 200)
            send(it.handle)
        }
        FakeHost.onResume = { send(it) }
        var wrong = 0
        client().prepareGet("https://takeout.example/media").execute { response ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                val at = read.value
                for (i in 0 until n) if (buffer[i] != ((at + i) % chunk.size % 251).toByte()) wrong++
                read.addAndGet(n.toLong())
            }
        }
        assertEquals(total.toLong(), read.value)
        assertEquals(0, wrong)
        assertTrue(pauses.value > 0, "the host was never asked to pause")
        // Never far ahead of the reader (without pauses it would be all 8 MB): our pause limit,
        // what Ktor's ByteChannel holds (its write and flush buffers, up to ~1 MB each) and a
        // chunk or two in flight. Measured: 2.8 MB.
        assertTrue(mostAhead <= HostRequest.PauseAbove + (2 shl 20) + 2 * chunk.size, "ran $mostAhead bytes ahead")
    }

    @Test
    fun cancellingTheCallCancelsTheHostsRequest() = runBlocking {
        val job = launch { client().get("https://takeout.example/slow") }
        withTimeout(5000) { while (FakeHost.seen.isEmpty()) delay(10) }
        job.cancel()
        job.join()
        val handle = FakeHost.seen.single().handle
        assertEquals(listOf(handle), FakeHost.cancels)
        FakeHost.end(handle, """{"kind":"cancelled"}""") // the host still ends it
    }

    @Test
    fun hostErrorsAreIOExceptions() = runBlocking {
        FakeHost.onStart = { FakeHost.end(it.handle, """{"kind":"network","message":"Connection refused"}""") }
        val error = assertFailsWith<IOException> { client().get("https://takeout.example/api/index") }
        assertContains(error.message.orEmpty(), "Connection refused")

        // Partway through the body: reading it fails.
        FakeHost.onStart = {
            FakeHost.head(it.handle, 200)
            FakeHost.data(it.handle, "half".encodeToByteArray())
            FakeHost.end(it.handle, """{"kind":"network","message":"reset"}""")
        }
        assertFailsWith<IOException> { client().get("https://takeout.example/api/index").bodyAsText() }
        Unit
    }

    @Test
    fun timeoutsGoToTheHost() = runBlocking {
        FakeHost.onStart = {
            FakeHost.head(it.handle, 200)
            FakeHost.end(it.handle)
        }
        HttpClient(Host) {
            install(HttpTimeout) {
                connectTimeoutMillis = 1234
                socketTimeoutMillis = 5678
            }
        }.get("https://takeout.example/")
        val request = FakeHost.seen.single().request
        assertEquals(1234, request.getValue("connectTimeoutMs").jsonPrimitive.long)
        assertEquals(5678, request.getValue("socketTimeoutMs").jsonPrimitive.long)
    }
}
