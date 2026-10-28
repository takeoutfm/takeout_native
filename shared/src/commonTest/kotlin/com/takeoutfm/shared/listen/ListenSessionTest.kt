package com.takeoutfm.shared.listen

import com.takeoutfm.shared.music.QueueItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

class ListenSessionTest {
    private var clock = Instant.fromEpochSeconds(1_000_000)
    private val sink = RecordingSink()
    private val session = ListenSession(sink) { clock }

    private fun pass(seconds: Int, durationMs: Long = 0) {
        clock += seconds.seconds
        session.tick(durationMs)
    }

    private val track = QueueItem("Loser", "Beck", "Mellow Gold", "", uri = "u", etag = "e1")
    private val stream = QueueItem("BAGeL Radio", "BAGeL Radio", "", "", uri = "s", etag = "")

    @Test
    fun aTrackCountsHalfWayThroughItsPlayingTime() {
        session.track(track)
        session.playing(true, 180_000)
        pass(89, 180_000)
        assertEquals(emptyList(), sink.listens)
        pass(1, 180_000)
        assertEquals(listOf("e1 Beck - Loser"), sink.listens)
        pass(100, 180_000)
        assertEquals(1, sink.listens.size)
        assertEquals(listOf("Beck - Loser"), sink.started)
    }

    @Test
    fun pausedTimeDoesNotCount() {
        session.track(track)
        session.playing(true, 180_000)
        pass(60, 180_000)
        session.playing(false, 180_000)
        pass(600, 180_000)
        assertEquals(emptyList(), sink.listens)
        session.playing(true, 180_000)
        pass(30, 180_000)
        assertEquals(listOf("e1 Beck - Loser"), sink.listens)
    }

    @Test
    fun aNewTrackStartsCountingAgain() {
        session.track(track)
        session.playing(true, 180_000)
        pass(80, 180_000)
        session.track(track.copy(etag = "e2", title = "Pay No Mind"))
        pass(80, 180_000)
        assertEquals(emptyList(), sink.listens)
    }

    @Test
    fun podcastsAndStreamsAreNotTrackListens() {
        session.track(track.copy(podcast = true))
        session.playing(true, 0)
        pass(600)
        session.track(stream)
        pass(600)
        assertEquals(emptyList(), sink.listens)
        assertEquals(emptyList(), sink.started)
    }

    @Test
    fun streamSongsCountWhenHeardFromTheirStart() {
        session.track(stream)
        session.playing(true, 0)
        session.streamTitle("Pixies - Gigantic") // tuned in mid-song
        pass(200)
        session.streamTitle("Wire - Outdoor Miner")
        pass(31)
        session.streamTitle("Station ID")
        assertEquals(listOf(" Wire - Outdoor Miner"), sink.listens)
        assertEquals(listOf("Pixies - Gigantic", "Wire - Outdoor Miner"), sink.started)
    }

    @Test
    fun theSongYouTuneInToNeedsFourMinutes() {
        session.track(stream)
        session.playing(true, 0)
        session.streamTitle("Pixies - Gigantic")
        pass(240)
        session.streamTitle("Wire - Outdoor Miner")
        assertEquals(listOf(" Pixies - Gigantic"), sink.listens)
    }

    @Test
    fun aStoppedStreamDoesNotCountItsSongAsHeardToTheEnd() {
        session.track(stream)
        session.playing(true, 0)
        session.streamTitle("Pixies - Gigantic")
        session.streamTitle("Wire - Outdoor Miner")
        pass(60)
        session.stopped()
        session.streamTitle("Wire - Outdoor Miner")
        pass(60)
        session.streamTitle("Pavement - Range Life")
        assertEquals(emptyList(), sink.listens)
    }

    private class RecordingSink : ListenSink {
        val started = mutableListOf<String>()
        val listens = mutableListOf<String>()

        override fun started(artist: String, title: String, album: String) {
            started += "$artist - $title"
        }

        override fun listened(etag: String, artist: String, title: String, album: String, at: Instant) {
            listens += "$etag $artist - $title"
        }
    }
}
