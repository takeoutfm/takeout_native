package com.takeoutfm.shared.api

import com.takeoutfm.shared.model.ApiTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The signed-in server + tokens. */
data class SessionState(
    val endpoint: String = "",
    val tokens: ApiTokens? = null,
) {
    val isLoggedIn: Boolean get() = endpoint.isNotEmpty() && tokens?.isValid() == true
}

/**
 * Where the session is kept: encrypted on each platform (EncryptedSharedPreferences on
 * Android, the Keychain on Apple).
 */
interface SecureStore {
    fun getString(key: String): String?
    fun putStrings(values: Map<String, String>)
    fun remove(vararg keys: String)
}

/** The signed-in server and tokens; [store] null keeps them in memory only (sign in each launch). */
class TakeoutSession(private val store: SecureStore?) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val endpoint: String get() = _state.value.endpoint
    val tokens: ApiTokens? get() = _state.value.tokens

    private fun load(): SessionState {
        val s = store ?: return SessionState()
        val access = s.getString(KEY_ACCESS)
        val media = s.getString(KEY_MEDIA)
        val refresh = s.getString(KEY_REFRESH)
        val tokens = if (access != null && media != null && refresh != null) {
            ApiTokens(access, media, refresh)
        } else {
            null
        }
        return SessionState(s.getString(KEY_ENDPOINT).orEmpty(), tokens)
    }

    fun signIn(endpoint: String, tokens: ApiTokens) {
        _state.value = SessionState(endpoint, tokens)
        store?.putStrings(
            mapOf(
                KEY_ENDPOINT to endpoint,
                KEY_ACCESS to tokens.accessToken,
                KEY_MEDIA to tokens.mediaToken,
                KEY_REFRESH to tokens.refreshToken,
            )
        )
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
        store?.remove(KEY_ACCESS, KEY_MEDIA, KEY_REFRESH)
    }

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_ACCESS = "access"
        const val KEY_MEDIA = "media"
        const val KEY_REFRESH = "refresh"
    }
}
