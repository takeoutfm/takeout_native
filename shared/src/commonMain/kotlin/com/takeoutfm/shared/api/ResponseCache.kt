package com.takeoutfm.shared.api

import com.takeoutfm.shared.db.DbWriter
import com.takeoutfm.shared.db.TakeoutDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/** Server responses kept for browsing offline (see [TakeoutApi.responseCache]). */
interface ResponseCache {
    fun get(key: String): String?
    fun put(key: String, body: String)
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

    override fun get(key: String): String? =
        runCatching { db.responseCacheQueries.get(key).executeAsOneOrNull() }.getOrNull()

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
