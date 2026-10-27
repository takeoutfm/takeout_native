package com.takeoutfm.shared.store

import platform.Foundation.NSUserDefaults

/**
 * A [KeyValueStore] over [NSUserDefaults], each key prefixed with the store's name (the
 * Android preferences file name), so the stores share the app's defaults without clashing.
 * The encrypted stores (session, ListenBrainz) are the app's Keychain store, in Swift.
 */
class UserDefaultsStore(private val name: String) : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    private fun key(k: String) = "$name.$k"

    override fun getString(key: String): String? = defaults.stringForKey(key(key))

    override fun putStrings(values: Map<String, String>) =
        values.forEach { (k, v) -> defaults.setObject(v, key(k)) }

    override fun remove(vararg keys: String) = keys.forEach { defaults.removeObjectForKey(key(it)) }
}
