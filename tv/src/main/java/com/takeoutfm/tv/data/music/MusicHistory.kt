package com.takeoutfm.tv.data.music

import android.content.Context
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * locally (no server support needed). Replaying one moves it to the front instead of adding a
 * copy. Cleared on sign-out, since its stream URLs belong to the signed-in server.
 */
@Singleton
class MusicHistory @Inject constructor(
    @ApplicationContext context: Context,
    session: TakeoutSession,
) {
    private val prefs = context.getSharedPreferences("music_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PlayedQueue.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<PlayedQueue>> = _entries.asStateFlow()

    init {
        scope.launch {
            session.state.collect {
                if (!it.isLoggedIn && _entries.value.isNotEmpty()) save(emptyList())
            }
        }
    }

    /** Records [queue] starting to play; returns its entry id for [progress]. */
    @Synchronized
    fun record(queue: MusicQueue, now: Long = System.currentTimeMillis()): String {
        val (list, id) = recorded(_entries.value, queue, now)
        save(list)
        return id
    }

    /** Where entry [id] got to, so replaying it resumes there. */
    @Synchronized
    fun progress(id: String, index: Int, positionMs: Long) {
        val list = _entries.value
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val entry = list[i]
        if (entry.index == index && entry.positionMs == positionMs) return
        val updated = list.toMutableList()
        updated[i] = entry.copy(index = index, positionMs = positionMs)
        save(updated)
    }

    fun find(id: String): PlayedQueue? = _entries.value.firstOrNull { it.id == id }

    private fun save(list: List<PlayedQueue>) {
        _entries.value = list
        val text = json.encodeToString(serializer, list)
        scope.launch { prefs.edit().putString(KEY, text).apply() }
    }

    private fun load(): List<PlayedQueue> =
        prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    companion object {
        const val MaxEntries = 50
        private const val KEY = "played"

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
