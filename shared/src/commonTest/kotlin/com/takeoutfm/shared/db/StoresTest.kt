package com.takeoutfm.shared.db

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.listen.ListenBrainzAccount
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.model.ApiTokens
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.music.StreamHistory
import com.takeoutfm.shared.progress.TakeoutProgress
import com.takeoutfm.shared.store.MemoryStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

class StoresTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var online = false
    private val posted = mutableListOf<String>()

    private val engine = MockEngine { request ->
        if (!online) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
        if (request.method.value == "POST") posted += request.url.encodedPath
        respond("""{"Offsets":[]}""", headers = json)
    }

    private val session = TakeoutSession(store = null).apply {
        signIn("https://takeout.example", ApiTokens("a", "m", "r"))
    }
    private val api = TakeoutApi(session, "test", engine)

    private fun pendingProgress(db: TakeoutDb) =
        db.progressQueries.all().executeAsList().filter { it.pending != 0L }.map { it.etag }

    @Test
    fun offlineProgressSurvivesARestartAndIsSentLater() = runTest {
        val db = testDb()
        TakeoutProgress(api, session, db).report("e1", 125_000, 3_600_000)
        eventually { pendingProgress(db) == listOf("e1") }

        // Restarted while still offline: the saved offset is still there for resume.
        val again = TakeoutProgress(api, session, db)
        assertEquals(125_000L, again.resumePositionMs("e1"))

        online = true
        again.sync()
        assertEquals(listOf("/api/progress"), posted)
        eventually { pendingProgress(db).isEmpty() }
        assertEquals(1, db.progressQueries.all().executeAsList().size)
    }

    @Test
    fun failedListensAreKeptUntilTheyAreSent() = runTest {
        val db = testDb()
        val reporter = ListenReporter(
            api, ListenBrainzAccount(store = MemoryStore()), db,
            reportPlays = { true }, submitListens = { false },
        )
        reporter.listened("e1", "Beck", "Loser", "Mellow Gold", Instant.fromEpochSeconds(1_700_000_000))
        eventually { db.listensQueries.all().executeAsList().size == 1 }
        assertEquals(1_700_000_000L, db.listensQueries.all().executeAsList().single().listened_at)

        online = true
        reporter.retry()
        eventually { db.listensQueries.all().executeAsList().isEmpty() }
        assertEquals(listOf("/api/activity"), posted)
    }

    private fun queue(location: String) = MusicQueue(
        title = location,
        creator = "",
        imageUri = "",
        items = listOf(QueueItem("t$location", "", "", "", "", "/u$location", "")),
        location = location,
    )

    @Test
    fun streamSongsAreKeptNewestFirstWithoutRepeats() = runTest {
        val db = testDb()
        val history = StreamHistory(db)
        history.heard("BAGeL Radio", "/img", "Beck - Loser", at = 1)
        history.heard("BAGeL Radio", "/img", "Beck - Loser", at = 2) // same song, new ICY event
        history.heard("BAGeL Radio", "/img", "Wilco - Jesus, Etc.", at = 3)
        eventually { db.streamHistoryQueries.all().executeAsList().size == 2 }
        assertEquals(listOf("Wilco - Jesus, Etc.", "Beck - Loser"), StreamHistory(db).songs.value.map { it.title })
        history.clear()
        eventually { db.streamHistoryQueries.all().executeAsList().isEmpty() }
    }

    @Test
    fun recentlyPlayedIsSavedNewestFirstWithItsPosition() = runTest {
        val db = testDb()
        val history = MusicHistory(session, db)
        history.record(queue("/a"), now = 1)
        val b = history.record(queue("/b"), now = 2)
        history.progress(b, index = 0, positionMs = 42_000)
        eventually { db.historyQueries.all().executeAsList().firstOrNull()?.position_ms == 42_000L }

        val reloaded = MusicHistory(session, db).entries.value
        assertEquals(listOf("/b", "/a"), reloaded.map { it.location })
        assertEquals(42_000L, reloaded.first().positionMs)
        assertTrue(reloaded.first().items.single().uri == "/u/b")
    }
}
