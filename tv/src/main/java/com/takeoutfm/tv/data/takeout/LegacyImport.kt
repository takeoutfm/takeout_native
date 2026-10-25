package com.takeoutfm.tv.data.takeout

import android.content.Context
import com.takeoutfm.shared.db.TakeoutDb
import com.takeoutfm.shared.listen.ListenReporter
import com.takeoutfm.shared.listen.PendingListen
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.PlayedQueue
import com.takeoutfm.shared.progress.instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Moves what builds before the shared database saved in SharedPreferences (as JSON) into the
 * database, once: offline progress, unsent listens and Recently Played. Each file is cleared
 * after its rows are in, so this does nothing on later launches.
 */
object LegacyImport {
    private val json = Json { ignoreUnknownKeys = true }

    /** The old listen queue's JSON shape. */
    @Serializable
    private data class OldListen(
        val etag: String,
        val artist: String,
        val title: String,
        val album: String,
        val listenedAt: Long,
        val toTakeout: Boolean,
        val toListenBrainz: Boolean,
    )

    fun run(context: Context, db: TakeoutDb) {
        importFile(context, "takeout_progress") { prefs ->
            val offsets = ListSerializer(ApiOffset.serializer())
            val pending = read(prefs.getString("pending", null), offsets).map { it.etag }.toSet()
            read(prefs.getString("local", null), offsets).forEach {
                db.progressQueries.upsert(
                    it.etag, it.offset.toLong(), it.duration.toLong(), it.date,
                    it.instant().toEpochMilliseconds(), if (it.etag in pending) 1 else 0,
                )
            }
        }
        importFile(context, "listen_reporter") { prefs ->
            read(prefs.getString("pending", null), ListSerializer(OldListen.serializer())).forEach {
                ListenReporter.insert(
                    db,
                    PendingListen(
                        it.etag, it.artist, it.title, it.album, it.listenedAt, it.toTakeout,
                        it.toListenBrainz,
                    ),
                )
            }
        }
        importFile(context, "music_history") { prefs ->
            read(prefs.getString("played", null), ListSerializer(PlayedQueue.serializer()))
                .forEach { MusicHistory.upsert(db, it) }
        }
    }

    private fun importFile(
        context: Context,
        name: String,
        block: (android.content.SharedPreferences) -> Unit,
    ) {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return
        runCatching { block(prefs) }
        prefs.edit().clear().commit()
    }

    private fun <T> read(text: String?, serializer: KSerializer<List<T>>): List<T> =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}
