package com.takeoutfm.shared.apple

import com.takeoutfm.shared.music.Album
import com.takeoutfm.shared.music.AlbumDetails
import com.takeoutfm.shared.music.MusicQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The shared calls the Swift app makes. Swift sees suspend functions as `async throws`, but a
 * Kotlin exception not declared with @Throws crashes the app instead of throwing, so every
 * call that can fail (network, missing title) goes through here.
 */
class SwiftCalls(private val core: TakeoutCore) {
    @Throws(Exception::class, CancellationException::class)
    suspend fun refreshLibrary() = core.library.refresh(maxAgeMs = 60_000)

    @Throws(Exception::class, CancellationException::class)
    suspend fun recentlyAdded(): List<Album> = core.music.recentlyAdded().first()

    @Throws(Exception::class, CancellationException::class)
    suspend fun newReleases(): List<Album> = core.music.newReleases().first()

    @Throws(Exception::class, CancellationException::class)
    suspend fun albumDetails(albumId: String): AlbumDetails = core.music.albumDetails(albumId)

    @Throws(Exception::class, CancellationException::class)
    suspend fun albumQueue(albumId: String, startIndex: Int): MusicQueue =
        core.music.albumQueue(albumId, startIndex)

    /** Starts [queue] in Recently Played; returns its entry id. */
    fun recordPlayed(queue: MusicQueue): String = core.history.record(queue)

    /** "Bearer <media token>" for a stream on the signed-in server, else null. */
    fun mediaAuthorization(url: String): String? = core.session.mediaAuthorization(url)

    val isSignedIn: Boolean get() = core.session.state.value.isLoggedIn
}
