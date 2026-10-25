package com.takeoutfm.shared.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * A small in-memory TTL cache for server responses: concurrent callers asking for the same key
 * share one request, and a load outlives any one caller (a caller leaving doesn't cancel it for
 * the rest). [scope] keys entries to the signed-in user so a new sign-in never sees old data.
 */
class RequestCache(private val scope: () -> String) {
    private class Entry(val at: Long, val value: Any)

    private val entries = HashMap<String, Entry>()
    private val inFlight = HashMap<String, Deferred<Any>>()
    private val lock = Mutex()
    private val loads = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun <T : Any> get(key: String, ttlMs: Long = 60_000, load: suspend () -> T): T {
        val scopedKey = "${scope()}:$key"
        val deferred = lock.withLock {
            val hit = entries[scopedKey]
            if (hit != null && now() - hit.at < ttlMs) {
                @Suppress("UNCHECKED_CAST")
                return hit.value as T
            }
            inFlight.getOrPut(scopedKey) {
                loads.async {
                    try {
                        val value: Any = load()
                        lock.withLock { entries[scopedKey] = Entry(now(), value) }
                        value
                    } finally {
                        lock.withLock { inFlight.remove(scopedKey) }
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return deferred.await() as T
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()
}
