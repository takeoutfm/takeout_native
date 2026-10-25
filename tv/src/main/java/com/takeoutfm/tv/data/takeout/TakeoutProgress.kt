package com.takeoutfm.tv.data.takeout

import android.content.Context
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.model.ApiOffsets
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.progress.ProgressRules.confirmed
import com.takeoutfm.shared.progress.ProgressRules.keepNewest
import com.takeoutfm.shared.progress.ProgressRules.newestByEtag
import com.takeoutfm.shared.progress.ProgressRules.selectInProgress
import com.takeoutfm.shared.progress.ProgressRules.shouldResume
import com.takeoutfm.shared.progress.instant
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Watch progress synced with the server's /api/progress, keyed by the media file's ETag.
 *
 * Every report is saved on the device first (newest per title) and marked pending until the
 * server accepts it, so progress made offline, or whose upload failed, isn't lost: pending
 * updates are resent with the next report, on app start and when the app comes back to the
 * foreground ([sync]). Resume and Continue Watching use the newer of the server's and the
 * device's offsets (by date, so another device's later progress still wins), and fall back to the
 * device's alone when the server can't be reached. Cleared on sign-out.
 */
@Singleton
class TakeoutProgress @Inject constructor(
    @ApplicationContext context: Context,
    private val api: TakeoutApi,
    session: TakeoutSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("takeout_progress", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(ApiOffset.serializer())
    private val lock = Mutex()

    /** Newest local offset per ETag. */
    private val local = ConcurrentHashMap<String, ApiOffset>(load(KEY_LOCAL).associateBy { it.etag })

    /** ETags whose local offset the server hasn't confirmed yet. */
    private val pending: MutableSet<String> =
        ConcurrentHashMap.newKeySet<String>().apply { addAll(load(KEY_PENDING).map { it.etag }) }

    init {
        scope.launch {
            session.state.collect { state ->
                if (!state.isLoggedIn) {
                    clear()
                } else {
                    sync()
                }
            }
        }
    }

    /** Where to resume [etag], in ms; 0 to start from the beginning. */
    suspend fun resumePositionMs(etag: String): Long {
        if (etag.isEmpty()) return 0
        val remote = try {
            api.progress().offsets.firstOrNull { it.etag == etag }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val newest = listOfNotNull(local[etag], remote).maxByOrNull { it.instant() } ?: return 0
        return if (shouldResume(newest.offset, newest.duration)) newest.offset * 1000L else 0
    }

    /**
     * Started-but-unfinished offsets updated within the last [days] days ([ProgressRules.AllTime]
     * for no limit), most recent first. Merges in local saves the server may not have yet.
     */
    suspend fun inProgress(days: Int): List<ApiOffset> =
        selectInProgress(latest().values, days, Clock.System.now())

    /** The newest offset for every ETag, from the server plus local saves (local alone offline). */
    suspend fun latest(): Map<String, ApiOffset> {
        val remote = try {
            api.progress().offsets
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return newestByEtag(remote + local.values)
    }

    fun report(etag: String, positionMs: Long, durationMs: Long) {
        if (etag.isEmpty() || positionMs < 0) return
        val duration = if (durationMs > 0) (durationMs / 1000).toInt() else 0
        var offset = (positionMs / 1000).toInt()
        if (duration > 0) offset = offset.coerceAtMost(duration)
        val previous = local[etag]
        if (previous != null && previous.offset == offset) return // nothing new to save
        local[etag] = ApiOffset(etag, offset, duration, Clock.System.now().toString())
        pending += etag
        save()
        scope.launch { sync() }
    }

    /** Sends every pending update in one request; what fails stays pending for next time. */
    suspend fun sync() {
        lock.withLock {
            val batch = pending.mapNotNull { local[it] }
            if (batch.isEmpty()) return
            try {
                api.updateProgress(ApiOffsets(batch))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return // offline or server error: retried on the next report, start or resume
            }
            pending.removeAll(confirmed(batch, local).toSet())
            save()
        }
    }

    private fun clear() {
        local.clear()
        pending.clear()
        prefs.edit().clear().apply()
    }

    private fun save() {
        // Keep the newest titles only; anything still pending is always kept.
        val kept = keepNewest(local.values, pending, MaxLocal)
        if (kept.size < local.size) {
            local.keys.retainAll(kept.map { it.etag }.toSet())
        }
        prefs.edit()
            .putString(KEY_LOCAL, json.encodeToString(listSerializer, kept))
            .putString(
                KEY_PENDING,
                json.encodeToString(listSerializer, pending.mapNotNull { local[it] }),
            )
            .apply()
    }

    private fun load(key: String): List<ApiOffset> =
        prefs.getString(key, null)
            ?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            .orEmpty()

    companion object {
        private const val MaxLocal = 500
        private const val KEY_LOCAL = "local"
        private const val KEY_PENDING = "pending"
    }
}
