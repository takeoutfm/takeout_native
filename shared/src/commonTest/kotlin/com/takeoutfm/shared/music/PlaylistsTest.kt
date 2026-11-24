package com.takeoutfm.shared.music

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.model.ApiTokens
import com.takeoutfm.shared.store.MemoryStore
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
import kotlinx.coroutines.test.runTest

/** [TakeoutMusicRepository.currentPlaylists] follows every playlist edit, from any page. */
class PlaylistsTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** A server with playlists 1 "Road Trip" (3 tracks) and 2 "Chill" (5); [failDeletes] refuses deletes. */
    private fun repository(failDeletes: Boolean = false): TakeoutMusicRepository {
        val session = TakeoutSession(store = null).apply {
            signIn("https://takeout.example", ApiTokens("access", "media", "refresh"))
        }
        var listed = """[{"ID":1,"Name":"Road Trip","TrackCount":3},{"ID":2,"Name":"Chill","TrackCount":5}]"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Get && path == "/api/playlists" ->
                    respond("""{"Playlists":$listed}""", headers = jsonHeaders)
                request.method == HttpMethod.Post && path == "/api/playlists" ->
                    respond("""{"ID":9,"Name":"Morning","TrackCount":0}""", headers = jsonHeaders)
                request.method == HttpMethod.Patch && path == "/api/playlists/2/playlist" -> {
                    // an album of 4 tracks added: the server's count, which only a reload shows
                    listed = """[{"ID":1,"Name":"Road Trip","TrackCount":4},{"ID":2,"Name":"Chill","TrackCount":9}]"""
                    respond("{}", headers = jsonHeaders)
                }
                request.method == HttpMethod.Delete && failDeletes -> respondError(HttpStatusCode.InternalServerError)
                else -> respond("{}", headers = jsonHeaders)
            }
        }
        val api = TakeoutApi(session, "test", engine)
        return TakeoutMusicRepository(api, TakeoutLibrary(api, session, MemoryStore()), TakeoutUrls(session))
    }

    private fun TakeoutMusicRepository.shown() =
        currentPlaylists.value?.map { "${it.name} ${it.trackCount}" }

    @Test
    fun editsUpdateTheCurrentPlaylists() = runTest {
        val music = repository()
        assertNull(music.currentPlaylists.value)
        music.playlists()
        assertEquals(listOf("Chill 5", "Road Trip 3"), music.shown())

        music.createPlaylist("  Morning ")
        assertEquals(listOf("Chill 5", "Morning 0", "Road Trip 3"), music.shown())

        music.addTrackToPlaylist("pl-1", "t-12")
        assertEquals(listOf("Chill 5", "Morning 0", "Road Trip 4"), music.shown())

        music.removeFromPlaylist("pl-1", 0)
        assertEquals(listOf("Chill 5", "Morning 0", "Road Trip 3"), music.shown())

        // the server's own count once it's reloaded (Morning isn't on the fake server)
        music.addAlbumToPlaylist("pl-2", "al-7")
        assertEquals(listOf("Chill 9", "Road Trip 4"), music.shown())

        music.deletePlaylist("pl-1")
        assertEquals(listOf("Chill 9"), music.shown())
    }

    @Test
    fun aRefusedDeleteKeepsThePlaylist() = runTest {
        val music = repository(failDeletes = true)
        music.playlists()
        assertFailsWith<Exception> { music.deletePlaylist("pl-1") }
        assertEquals(listOf("Chill 5", "Road Trip 3"), music.shown())
    }

    @Test
    fun editsBeforeTheFirstLoadLeaveItUnloaded() = runTest {
        val music = repository()
        music.createPlaylist("Morning")
        music.deletePlaylist("pl-1")
        assertNull(music.currentPlaylists.value)
    }

    @Test
    fun coversAreTheFirstFourDifferentOnes() {
        fun item(cover: String) = QueueItem("t", "a", "b", imageUri = cover, uri = "u", etag = cover)
        val queue = MusicQueue("p", "", "", listOf("a", "a", "", "b", "c", "b", "d", "e").map(::item))
        assertEquals(listOf("a", "b", "c", "d"), TakeoutMusicRepository.coversOf(queue))
        assertEquals(listOf("a"), TakeoutMusicRepository.coversOf(queue.copy(items = queue.items.take(2))))
    }

    @Test
    fun favoriteArtistsFollowEdits() = runTest {
        val session = TakeoutSession(store = null).apply {
            signIn("https://takeout.example", ApiTokens("access", "media", "refresh"))
        }
        var refuse = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/favorite" -> respond(
                    """{"Artists":[{"ID":1,"Name":"Beck","ARID":"a-beck"}],"Tracks":[]}""",
                    headers = jsonHeaders,
                )
                refuse -> respondError(HttpStatusCode.InternalServerError)
                else -> respond("{}", headers = jsonHeaders)
            }
        }
        val api = TakeoutApi(session, "test", engine)
        val music = TakeoutMusicRepository(api, TakeoutLibrary(api, session, MemoryStore()), TakeoutUrls(session))
        music.favorites()
        assertEquals(setOf("a-beck"), music.favoriteArtistIds.value)
        music.setArtistFavorite("a-wilco", true)
        music.setArtistFavorite("a-beck", false)
        assertEquals(setOf("a-wilco"), music.favoriteArtistIds.value)
        refuse = true
        assertFailsWith<Exception> { music.setArtistFavorite("a-wilco", false) }
        assertEquals(setOf("a-wilco"), music.favoriteArtistIds.value)
    }
}
