package com.takeoutfm.shared.music

import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.db.DbWriter
import com.takeoutfm.shared.db.TakeoutDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A queue that was played, as Recently Played remembers it. */
@Serializable
data class PlayedQueue(
    /** Stable while the entry exists; the Recently Played route refers to it. */
    val id: String,
    val title: String,
    val creator: String = "",
    val imageUri: String = "",
    /** The server's playlist location; matches a replay of the same album, mix or station. */
    val location: String = "",
    val items: List<PlayedItem>,
    /** Where it was left. */
    val index: Int = 0,
    val positionMs: Long = 0,
    val playedAt: Long = 0,
) {
    fun toQueue() = MusicQueue(
        title = title,
        creator = creator,
        imageUri = imageUri,
        items = items.map { it.toQueueItem() },
        startIndex = index.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        startPositionMs = positionMs,
        location = location,
    )

    /** The cover shown for it: the queue's own image, else its first track's. */
    val coverUri: String get() = imageUri.ifEmpty { items.firstOrNull()?.imageUri.orEmpty() }
}

@Serializable
data class PlayedItem(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val imageUri: String = "",
    val backgroundUri: String = "",
    val uri: String,
    val etag: String = "",
) {
    fun toQueueItem() = QueueItem(title, artist, album, imageUri, backgroundUri, uri, etag)
}

/**
 * Recently Played: the last [MaxEntries] queues played on this device, newest first, saved
 * locally (the `played_queue` table; no server support needed). Replaying one moves it to the
 * front instead of adding a copy. Cleared on sign-out, since its stream URLs belong to the
 * signed-in server.
 */
class MusicHistory(
    session: TakeoutSession,
    private val db: TakeoutDb,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writer = DbWriter()

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<PlayedQueue>> = _entries.asStateFlow()

    init {
        scope.launch {
            session.state.collect {
                if (!it.isLoggedIn && _entries.value.isNotEmpty()) {
                    _entries.value = emptyList()
                    writer.write { db.historyQueries.clear() }
                }
            }
        }
    }

    /** Records [queue] starting to play; returns its entry id for [progress]. */
    fun record(queue: MusicQueue, now: Long = Clock.System.now().toEpochMilliseconds()): String {
        var id = ""
        var list = emptyList<PlayedQueue>()
        _entries.update { current ->
            recorded(current, queue, now).also { (l, i) -> list = l; id = i }.first
        }
        val entry = list.first()
        val ids = list.map { it.id }
        writer.write {
            db.historyQueries.transaction {
                upsert(db, entry)
                db.historyQueries.keepOnly(ids)
            }
        }
        return id
    }

    /** Where entry [id] got to, so replaying it resumes there. */
    fun progress(id: String, index: Int, positionMs: Long) {
        var changed = false
        _entries.update { list ->
            val i = list.indexOfFirst { it.id == id }
            val entry = list.getOrNull(i)
            if (entry == null || (entry.index == index && entry.positionMs == positionMs)) {
                changed = false
                list
            } else {
                changed = true
                list.toMutableList().also { it[i] = entry.copy(index = index, positionMs = positionMs) }
            }
        }
        if (changed) {
            writer.write { db.historyQueries.progress(index.toLong(), positionMs, id) }
        }
    }

    fun find(id: String): PlayedQueue? = _entries.value.firstOrNull { it.id == id }

    private fun load(): List<PlayedQueue> =
        db.historyQueries.all().executeAsList().mapNotNull {
            val items = runCatching { json.decodeFromString(itemsSerializer, it.items) }
                .getOrNull() ?: return@mapNotNull null
            PlayedQueue(
                id = it.id,
                title = it.title,
                creator = it.creator,
                imageUri = it.image_uri,
                location = it.location,
                items = items,
                index = it.item_index.toInt(),
                positionMs = it.position_ms,
                playedAt = it.played_at,
            )
        }

    companion object {
        const val MaxEntries = 50
        private val json = Json { ignoreUnknownKeys = true }
        private val itemsSerializer = ListSerializer(PlayedItem.serializer())

        fun upsert(db: TakeoutDb, q: PlayedQueue) = db.historyQueries.upsert(
            q.id, q.title, q.creator, q.imageUri, q.location,
            json.encodeToString(itemsSerializer, q.items),
            q.index.toLong(), q.positionMs, q.playedAt,
        )

        /** Same album/mix/station: by location when the server gave one, else by contents. */
        private fun sameQueue(entry: PlayedQueue, queue: MusicQueue): Boolean =
            if (queue.location.isNotEmpty()) {
                entry.location == queue.location
            } else {
                entry.location.isEmpty() && entry.title == queue.title &&
                    entry.items.firstOrNull()?.uri == queue.items.firstOrNull()?.uri
            }

        /** [list] with [queue] at the front (replacing an earlier play of it), capped. */
        fun recorded(
            list: List<PlayedQueue>,
            queue: MusicQueue,
            now: Long,
        ): Pair<List<PlayedQueue>, String> {
            val previous = list.firstOrNull { sameQueue(it, queue) }
            val id = previous?.id ?: "h$now"
            val entry = PlayedQueue(
                id = id,
                title = queue.title,
                creator = queue.creator,
                imageUri = queue.imageUri,
                location = queue.location,
                items = queue.items.map {
                    PlayedItem(
                        it.title, it.artist, it.album, it.imageUri, it.backgroundUri, it.uri,
                        it.etag,
                    )
                },
                index = queue.startIndex,
                positionMs = queue.startPositionMs,
                playedAt = now,
            )
            return (listOf(entry) + list.filter { it.id != id }).take(MaxEntries) to id
        }
    }
}
