package com.takeoutfm.mobile

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takeoutfm.mobile.playback.MusicService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Browses the music service like Android Auto does, against the signed-in debug app (run with
 * `am instrument` so the app and its sign-in stay installed). Plays one album for a few
 * seconds, under any listen rule.
 */
@RunWith(AndroidJUnit4::class)
class BrowseTreeTest {
    private lateinit var browser: MediaBrowser
    private val main = InstrumentationRegistry.getInstrumentation()

    @Before
    fun connect() {
        val context = main.targetContext
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        browser = onMain { MediaBrowser.Builder(context, token).buildAsync() }.get(10, TimeUnit.SECONDS)
    }

    @After
    fun release() = onMain {
        browser.pause()
        browser.release()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        main.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun children(id: String): List<MediaItem> =
        onMain { browser.getChildren(id, 0, 200, null) }.get(30, TimeUnit.SECONDS).value.orEmpty()

    @Test
    fun theTreeHasTheTakeoutFolders() {
        val root = onMain { browser.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS).value!!
        val folders = children(root.mediaId)
        assertEquals(listOf("recent", "new", "added", "radio", "playlists", "podcasts"), folders.map { it.mediaId })
        assertTrue(folders.all { it.mediaMetadata.isBrowsable == true })
        val radio = children("radio")
        assertTrue("stations", radio.isNotEmpty())
        assertTrue(radio.all { it.mediaMetadata.isPlayable == true && it.mediaId.startsWith("play:station:") })
        assertTrue("new releases", children("new").isNotEmpty())
    }

    @Test
    fun searchFindsMusic() {
        onMain { browser.search("beck", null) }.get(10, TimeUnit.SECONDS)
        val results = onMain { browser.getSearchResult("beck", 0, 50, null) }.get(30, TimeUnit.SECONDS).value.orEmpty()
        assertTrue("results", results.isNotEmpty())
    }

    @Test
    fun playingAnAlbumQueuesAllOfIt() {
        val album = children("new").first()
        onMain {
            browser.setMediaItem(album)
            browser.prepare()
            browser.play()
        }
        var count = 0
        repeat(20) {
            Thread.sleep(250)
            count = onMain { browser.mediaItemCount }
            if (count > 1) return@repeat
        }
        assertTrue("queue of $count", count > 1)
    }
}
