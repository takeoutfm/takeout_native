package com.takeoutfm.tv.data.takeout

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.takeoutfm.shared.api.SecureStore
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the shared Takeout session and API client to the TV app. */
@Module
@InstallIn(SingletonComponent::class)
object TakeoutModule {
    @Provides
    @Singleton
    fun session(@ApplicationContext context: Context): TakeoutSession =
        TakeoutSession(EncryptedStore.open(context))

    @Provides
    @Singleton
    fun api(session: TakeoutSession): TakeoutApi =
        TakeoutApi(session, "TakeoutFM-TV/1.0 (takeoutfm.com; Android ${Build.VERSION.RELEASE})")
}

/** The session in EncryptedSharedPreferences ("takeout_session", as before the shared core). */
private class EncryptedStore(private val prefs: SharedPreferences) : SecureStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putStrings(values: Map<String, String>) {
        prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    override fun remove(vararg keys: String) {
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    companion object {
        /** Null on Keystore problems: the session is then kept in memory only. */
        fun open(context: Context): SecureStore? = try {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedStore(
                EncryptedSharedPreferences.create(
                    "takeout_session",
                    alias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            )
        } catch (e: Exception) {
            null
        }
    }
}
