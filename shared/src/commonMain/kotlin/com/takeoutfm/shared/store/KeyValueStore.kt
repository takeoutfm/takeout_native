package com.takeoutfm.shared.store

/**
 * Small string values the shared core keeps on the device (session, saved library index,
 * account tokens). Each platform provides plain and encrypted implementations.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putStrings(values: Map<String, String>)
    fun remove(vararg keys: String)

    fun putString(key: String, value: String) = putStrings(mapOf(key to value))
}

/** A [KeyValueStore] in memory, for tests and when the platform store can't be opened. */
class MemoryStore : KeyValueStore {
    private val values = HashMap<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putStrings(values: Map<String, String>) = this.values.putAll(values)
    override fun remove(vararg keys: String) = keys.forEach { values.remove(it) }
}
