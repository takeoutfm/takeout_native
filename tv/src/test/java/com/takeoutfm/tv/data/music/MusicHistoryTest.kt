package com.takeoutfm.tv.data.music

import com.takeoutfm.tv.data.entities.music.MusicQueue
import com.takeoutfm.tv.data.entities.music.QueueItem
import com.takeoutfm.tv.data.music.MusicHistory.Companion.MaxEntries
import com.takeoutfm.tv.data.music.MusicHistory.Companion.recorded
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicHistoryTest {
    private fun queue(location: String, title: String = location, first: String = "u-$title") =
        MusicQueue(
            title = title,
            creator = "",
            imageUri = "",
            items = listOf(QueueItem(title, "", "", "", "", first, "")),
            location = location,
        )

    @Test
    fun newestFirstAndReplayMovesToFront() {
        var list = emptyList<PlayedQueue>()
        list = recorded(list, queue("/a"), 1).first
        list = recorded(list, queue("/b"), 2).first
        val (again, id) = recorded(list, queue("/a"), 3)
        assertEquals(listOf("/a", "/b"), again.map { it.location })
        assertEquals("h1", id) // keeps its id, so a route to it still works
    }

    @Test
    fun withoutLocationMatchesByTitleAndFirstTrack() {
        var list = recorded(emptyList(), queue("", "Mix", "x"), 1).first
        list = recorded(list, queue("", "Mix", "x"), 2).first
        assertEquals(1, list.size)
        list = recorded(list, queue("", "Mix", "y"), 3).first
        assertEquals(2, list.size)
    }

    @Test
    fun cappedAtMaxEntries() {
        var list = emptyList<PlayedQueue>()
        repeat(MaxEntries + 5) { list = recorded(list, queue("/$it"), it.toLong()).first }
        assertEquals(MaxEntries, list.size)
        assertEquals("/${MaxEntries + 4}", list.first().location)
    }
}
