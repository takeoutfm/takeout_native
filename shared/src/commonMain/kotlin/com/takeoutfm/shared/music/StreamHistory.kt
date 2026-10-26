package com.takeoutfm.shared.music

import com.takeoutfm.shared.db.DbWriter
import com.takeoutfm.shared.db.TakeoutDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock

/** A song heard on a live stream. */
data class StreamSong(
    val station: String,
    val imageUri: String,
    /** The stream's title for it, usually "Artist - Title". */
    val title: String,
    /** Epoch ms. */
    val heardAt: Long,
)

/**
 * Stream History: the songs heard on live streams on this device (from the streams' ICY
 * titles), newest first, up to [MaxSongs]. The music service records them as titles change.
 */
class StreamHistory(private val db: TakeoutDb) {
    private val writer = DbWriter()

    private val _songs = MutableStateFlow(
        db.streamHistoryQueries.all().executeAsList().map {
            StreamSong(it.station, it.image_uri, it.title, it.heard_at)
        }
    )
    val songs: StateFlow<List<StreamSong>> = _songs.asStateFlow()

    /** [title] started on [station]; a repeat of the last title on the same station is ignored. */
    fun heard(station: String, imageUri: String, title: String, at: Long = Clock.System.now().toEpochMilliseconds()) {
        if (title.isBlank()) return
        val last = _songs.value.firstOrNull()
        if (last != null && last.station == station && last.title == title) return
        val song = StreamSong(station, imageUri, title.trim(), at)
        _songs.update { (listOf(song) + it).take(MaxSongs) }
        writer.write {
            db.streamHistoryQueries.transaction {
                db.streamHistoryQueries.insert(song.station, song.imageUri, song.title, song.heardAt)
                db.streamHistoryQueries.prune(MaxSongs.toLong())
            }
        }
    }

    fun clear() {
        _songs.value = emptyList()
        writer.write { db.streamHistoryQueries.clear() }
    }

    companion object {
        const val MaxSongs = 200
    }
}
