package com.takeoutfm.tv.data.takeout

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The signed-in server + tokens. Persisted in EncryptedSharedPreferences. */
data class SessionState(
    val endpoint: String = "",
    val tokens: ApiTokens? = null,
) {
    val isLoggedIn: Boolean get() = endpoint.isNotEmpty() && tokens?.isValid() == true
}

@Singleton
class TakeoutSession @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences? = try {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "takeout_session",
            alias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore problems: fall back to an in-memory session (user signs in each launch).
        null
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val endpoint: String get() = _state.value.endpoint
    val tokens: ApiTokens? get() = _state.value.tokens

    private fun load(): SessionState {
        val p = prefs ?: return SessionState()
        val access = p.getString(KEY_ACCESS, null)
        val media = p.getString(KEY_MEDIA, null)
        val refresh = p.getString(KEY_REFRESH, null)
        val tokens = if (access != null && media != null && refresh != null) {
            ApiTokens(access, media, refresh)
        } else {
            null
        }
        return SessionState(p.getString(KEY_ENDPOINT, "") ?: "", tokens)
    }

    fun signIn(endpoint: String, tokens: ApiTokens) {
        _state.value = SessionState(endpoint, tokens)
        prefs?.edit()
            ?.putString(KEY_ENDPOINT, endpoint)
            ?.putString(KEY_ACCESS, tokens.accessToken)
            ?.putString(KEY_MEDIA, tokens.mediaToken)
            ?.putString(KEY_REFRESH, tokens.refreshToken)
            ?.apply()
    }

    /** Called after a token refresh; the media token is unchanged. */
    fun updateTokens(access: String, refresh: String) {
        val current = _state.value
        val media = current.tokens?.mediaToken ?: return
        signIn(current.endpoint, ApiTokens(access, media, refresh))
    }

    /**
     * Streams are authorized with the media token (not the access token). Only attach it to
     * requests going to the signed-in server so it is never sent to third parties.
     */
    fun mediaAuthorization(url: String): String? {
        val state = _state.value
        val media = state.tokens?.mediaToken ?: return null
        return if (state.endpoint.isNotEmpty() && url.startsWith(state.endpoint)) {
            "Bearer $media"
        } else {
            null
        }
    }

    fun signOut() {
        val endpoint = _state.value.endpoint
        _state.value = SessionState(endpoint = endpoint, tokens = null)
        prefs?.edit()
            ?.remove(KEY_ACCESS)?.remove(KEY_MEDIA)?.remove(KEY_REFRESH)
            ?.apply()
    }

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_ACCESS = "access"
        const val KEY_MEDIA = "media"
        const val KEY_REFRESH = "refresh"
    }
}
