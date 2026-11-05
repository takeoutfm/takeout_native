package com.takeoutfm.android

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takeoutfm.media.SavedRepeat
import com.takeoutfm.shared.settings.PlaySettings
import com.takeoutfm.shared.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Repeat All kept across queues and new players; Repeat One only for the queue playing. Nothing plays. */
@RunWith(AndroidJUnit4::class)
class SavedRepeatTest {
    private val main = InstrumentationRegistry.getInstrumentation()
    private val settings = PlaySettings(MemoryStore())

    private fun items(vararg ids: String) = ids.map { MediaItem.Builder().setMediaId(it).setUri("http://localhost/$it").build() }

    /** A new player (as when the service starts) with [SavedRepeat]; released after [block]. */
    private fun withPlayer(block: (ExoPlayer) -> Unit) = main.runOnMainSync {
        val player = ExoPlayer.Builder(main.targetContext).build()
        val repeat = SavedRepeat(player, settings).also { it.attach() }
        try {
            block(player)
        } finally {
            repeat.detach()
            player.release()
        }
    }

    @Test
    fun repeatAllIsKeptForNewQueuesAndNewPlayers() {
        withPlayer { player ->
            assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
            player.setMediaItems(items("a", "b"))
            player.repeatMode = Player.REPEAT_MODE_ALL
            assertTrue(settings.repeatAll.value)
            player.setMediaItems(items("c", "d"))
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        }
        withPlayer { player -> assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode) }
    }

    @Test
    fun repeatOneLastsUntilSomethingElseIsPlayed() {
        withPlayer { player ->
            player.setMediaItems(items("a", "b"))
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.repeatMode = Player.REPEAT_MODE_ONE
            assertTrue(settings.repeatAll.value) // One isn't saved
            player.addMediaItems(items("c")) // adding to the queue keeps it
            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
            player.setMediaItems(items("d", "e"))
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        }
        withPlayer { player -> assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode) }
    }

    @Test
    fun turningRepeatOffIsKept() {
        settings.setRepeatAll(true)
        withPlayer { player ->
            player.repeatMode = Player.REPEAT_MODE_OFF
            assertFalse(settings.repeatAll.value)
        }
        withPlayer { player -> assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode) }
    }
}
