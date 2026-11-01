package com.takeoutfm.shared.store

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The secrets the app keeps for the shared core (session and ListenBrainz tokens), held by the
 * C++ app in the Secret Service (KWallet on Plasma) as one JSON object. It starts from [saved],
 * the JSON the app loaded, and hands the whole object to [save] after every change (from any
 * thread; [save] mustn't call back into the store). [named] gives each user its own keys, as Android's separate preferences files do.
 */
class SecretStore(saved: String?, private val save: (String) -> Unit) {
    private val lock = PosixLock()
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var values: Map<String, String> =
        saved?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    fun named(name: String): KeyValueStore = object : KeyValueStore {
        override fun getString(key: String): String? = lock.locked { values["$name.$key"] }

        override fun putStrings(values: Map<String, String>) =
            update { it + values.mapKeys { (k, _) -> "$name.$k" } }

        override fun remove(vararg keys: String) = update { it - keys.map { k -> "$name.$k" }.toSet() }
    }

    private fun update(change: (Map<String, String>) -> Map<String, String>) {
        // Saved under the lock, so the app always ends up with the latest.
        lock.locked {
            val updated = change(values)
            if (updated == values) return
            values = updated
            save(Json.encodeToString(serializer, updated))
        }
    }
}
