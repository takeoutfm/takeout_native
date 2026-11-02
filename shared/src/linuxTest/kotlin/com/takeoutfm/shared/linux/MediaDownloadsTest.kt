@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.linux

import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.store.SecretStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.mkdtemp
import platform.posix.system
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaDownloadsTest {
    private val server = "https://takeout.example"
    private val dir: String = memScoped {
        mkdtemp("/tmp/takeout-downloads-XXXXXX".cstr.getPointer(this))!!.toKString()
    }
    private val session = TakeoutSession(
        SecretStore(
            """{"takeout_session.endpoint":"$server","takeout_session.access":"a",""" +
                """"takeout_session.media":"m","takeout_session.refresh":"r"}""",
        ) {}.named("takeout_session"),
    )
    /** Each request's URL and Authorization header. */
    private val requests = mutableListOf<Pair<String, String?>>()

    private val engine = MockEngine { request ->
        requests += request.url.toString() to request.headers[HttpHeaders.Authorization]
        when (request.url.host) {
            "takeout.example" -> when (request.url.encodedPath) {
                "/api/tracks/1/location" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://storage.example/b/one.flac?sig=1"))
                else -> respondError(HttpStatusCode.NotFound)
            }
            else -> respond("flac bytes", headers = headersOf(HttpHeaders.ContentLength, "10"))
        }
    }

    @AfterTest
    fun cleanUp() {
        system("rm -rf '$dir'")
    }

    private fun item(n: Int) = QueueItem(
        title = "Track $n", artist = "Artist", album = "Album", imageUri = "",
        uri = "$server/api/tracks/$n/location", etag = "\"etag-$n\"",
    )

    private fun MediaDownloads.settled(count: Int): Map<String, Download> = runBlocking {
        withTimeout(5_000) {
            downloads.first { all -> all.size == count && all.values.none { it.state <= DownloadState.Downloading } }
        }
    }

    @Test
    fun downloadsFollowTheRedirectWithoutTheTokenAndKeepTheFile() {
        val downloads = MediaDownloads(dir, session, "test", engine)
        val queue = MusicQueue("Album", "Artist", "", listOf(item(1), item(2), item(3).copy(etag = "")), location = "/api/releases/9/playlist")
        downloads.download(queue)

        val done = downloads.settled(2)
        val one = done.getValue("\"etag-1\"")
        assertEquals(DownloadState.Done, one.state)
        assertEquals("/api/releases/9/playlist", one.entry.group)
        assertEquals(10, one.entry.bytes)
        assertEquals("$dir/media/etag-1.flac", one.path)
        assertEquals("flac bytes", read(one.path!!))
        assertEquals(DownloadState.Failed, done.getValue("\"etag-2\"").state) // the server had no track 2

        // The media token went to Takeout, never to storage.
        assertEquals("Bearer m", requests.first { it.first.endsWith("/tracks/1/location") }.second)
        assertNull(requests.first { it.first.startsWith("https://storage.example") }.second)

        // Kept across restarts.
        val again = MediaDownloads(dir, session, "test", engine)
        assertEquals(one.path, again.localPath("\"etag-1\""))

        again.remove(listOf("\"etag-1\""))
        assertNull(again.localPath("\"etag-1\""))
        assertTrue(access("$dir/media/etag-1.flac", F_OK) != 0)
    }

    private fun read(path: String): String {
        val file = fopen(path, "r")!!
        val buffer = ByteArray(256)
        val text = fgets(buffer.refTo(0), buffer.size, file)?.toKString().orEmpty()
        fclose(file)
        return text
    }
}
