package com.takeoutfm.shared.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** A [KeyValueStore] over [SharedPreferences]. */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putStrings(values: Map<String, String>) {
        prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    override fun remove(vararg keys: String) {
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }
}

object AndroidStores {
    /** The plain preferences file [name]. */
    fun plain(context: Context, name: String): KeyValueStore =
        SharedPreferencesStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    /** The EncryptedSharedPreferences file [name]; null on Keystore problems. */
    fun encrypted(context: Context, name: String): KeyValueStore? = try {
        SharedPreferencesStore(
            EncryptedSharedPreferences.create(
                name,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        )
    } catch (e: Exception) {
        null
    }
}
