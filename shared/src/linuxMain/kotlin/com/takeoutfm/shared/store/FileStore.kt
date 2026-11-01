@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.store

import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.rename

/**
 * A [KeyValueStore] in a JSON file, "[name].json" in [directory] (which must exist): the Linux
 * counterpart of Android's preferences files and the Mac's user defaults. Every change rewrites
 * the file (write a temporary file, then rename), so a crash never leaves it half written.
 * The encrypted stores (session, ListenBrainz) are the app's Secret Service store, in C++.
 */
class FileStore(directory: String, name: String) : KeyValueStore {
    private val path = "$directory/$name.json"
    private val lock = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var values: Map<String, String> = read()

    override fun getString(key: String): String? = locked { values[key] }

    override fun putStrings(values: Map<String, String>) = locked { save(this.values + values) }

    override fun remove(vararg keys: String) = locked { save(values - keys.toSet()) }

    private fun save(updated: Map<String, String>) {
        if (updated == values) return
        values = updated
        val temp = "$path.tmp"
        val file = fopen(temp, "w") ?: return
        try {
            fputs(Json.encodeToString(serializer, updated), file)
        } finally {
            fclose(file)
        }
        rename(temp, path)
    }

    private fun read(): Map<String, String> {
        if (access(path, F_OK) != 0) return emptyMap()
        val file = fopen(path, "r") ?: return emptyMap()
        val text = try {
            buildString {
                val buffer = ByteArray(8192)
                while (true) {
                    val line = fgets(buffer.refTo(0), buffer.size, file) ?: break
                    append(line.toKString())
                }
            }
        } finally {
            fclose(file)
        }
        return runCatching { Json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())
    }

    private inline fun <T> locked(block: () -> T): T {
        pthread_mutex_lock(lock.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(lock.ptr)
        }
    }
}
