@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.takeoutfm.shared.store

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
import platform.posix.rename

/**
 * A [KeyValueStore] in a JSON file, "[name].json" in [directory] (which must exist): the Linux
 * counterpart of Android's preferences files and the Mac's user defaults. Every change rewrites
 * the file (write a temporary file, then rename), so a crash never leaves it half written.
 * The encrypted stores (session, ListenBrainz) are the app's Secret Service store, in C++.
 */
class FileStore(directory: String, name: String) : KeyValueStore {
    private val path = "$directory/$name.json"
    private val lock = PosixLock()
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var values: Map<String, String> = read()

    override fun getString(key: String): String? = lock.locked { values[key] }

    override fun putStrings(values: Map<String, String>) = lock.locked { save(this.values + values) }

    override fun remove(vararg keys: String) = lock.locked { save(values - keys.toSet()) }

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
}
