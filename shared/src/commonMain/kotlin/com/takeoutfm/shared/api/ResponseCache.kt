package com.takeoutfm.shared.api

import com.takeoutfm.shared.db.DbWriter
import com.takeoutfm.shared.db.TakeoutDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/** Server responses kept for reuse and for browsing offline (see [TakeoutApi.responseCache]). */
interface ResponseCache {
    /** The last response kept for [key], or null. */
    fun get(key: String): Kept?
    fun put(key: String, body: String)

    data class Kept(val body: String, /** Epoch ms. */ val savedAt: Long)
}

/**
 * [ResponseCache] in the shared database (`http_cache`): the last response for each request,
 * written in the background; cleared on sign-out (it's the old account's library).
 */
class DbResponseCache(private val db: TakeoutDb, session: TakeoutSession) : ResponseCache {
    private val writer = DbWriter()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            session.state.collect { if (!it.isLoggedIn) writer.write { db.responseCacheQueries.clear() } }
        }
    }

    override fun get(key: String): ResponseCache.Kept? = runCatching {
        db.responseCacheQueries.getKept(key).executeAsOneOrNull()?.let { ResponseCache.Kept(it.body, it.saved_at) }
    }.getOrNull()

    override fun put(key: String, body: String) {
        if (body.length > MaxBodyChars) return
        val now = Clock.System.now().toEpochMilliseconds()
        writer.write { db.responseCacheQueries.put(key, body, now) }
    }

    private companion object {
        /** Very large responses (a huge movie library) aren't kept. */
        const val MaxBodyChars = 8_000_000
    }
}
