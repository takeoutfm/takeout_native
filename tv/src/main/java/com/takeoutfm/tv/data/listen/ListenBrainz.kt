package com.takeoutfm.tv.data.listen

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A track as ListenBrainz identifies it (by names, not ids). */
@Serializable
data class ListenTrack(
    @SerialName("artist_name") val artist: String,
    @SerialName("track_name") val title: String,
    @SerialName("release_name") val release: String? = null,
)

@Serializable
private data class Listen(
    @SerialName("listened_at") val listenedAt: Long? = null,
    @SerialName("track_metadata") val track: ListenTrack,
)

@Serializable
private data class Submission(
    @SerialName("listen_type") val type: String,
    @SerialName("payload") val payload: List<Listen>,
)

@Serializable
private data class TokenCheck(
    @SerialName("valid") val valid: Boolean = false,
    @SerialName("user_name") val userName: String = "",
)

/** The connected ListenBrainz user, or null. */
data class ListenBrainzUser(val name: String)

/**
 * The ListenBrainz account on this device: the user token (stored encrypted, like the Takeout
 * session) and the ListenBrainz API calls that use it.
 */
@Singleton
class ListenBrainzAccount @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            "listenbrainz",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _user = MutableStateFlow(
        prefs?.getString(KEY_USER, null)?.let { ListenBrainzUser(it) }
    )
    val user: StateFlow<ListenBrainzUser?> = _user.asStateFlow()

    private val token: String? get() = prefs?.getString(KEY_TOKEN, null)

    /**
     * Checks [token] with ListenBrainz and, if it's valid, saves it; returns the user, or null
     * for a token ListenBrainz doesn't accept. Throws on network errors.
     */
    suspend fun connect(token: String): ListenBrainzUser? {
        val clean = token.trim()
        if (clean.isEmpty()) return null
        val check = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$Api/1/validate-token")
                .header("Authorization", "Token $clean")
                .build()
            http.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful && response.code != 401) {
                    throw IOException("ListenBrainz HTTP ${response.code}")
                }
                runCatching { json.decodeFromString(TokenCheck.serializer(), body) }
                    .getOrDefault(TokenCheck())
            }
        }
        if (!check.valid) return null
        val user = ListenBrainzUser(check.userName)
        prefs?.edit()?.putString(KEY_TOKEN, clean)?.putString(KEY_USER, user.name)?.apply()
        _user.value = user
        return user
    }

    fun disconnect() {
        prefs?.edit()?.remove(KEY_TOKEN)?.remove(KEY_USER)?.apply()
        _user.value = null
    }

    /** Submits finished listens ([listenedAt] in Unix seconds). Throws when it can't. */
    suspend fun submitListens(listens: List<Pair<ListenTrack, Long>>) {
        if (listens.isEmpty()) return
        submit(
            Submission(
                type = if (listens.size > 1) "import" else "single",
                payload = listens.map { (track, at) -> Listen(at, track) },
            )
        )
    }

    /** Tells ListenBrainz what's playing right now (shown on the profile; not stored). */
    suspend fun playingNow(track: ListenTrack) =
        submit(Submission("playing_now", listOf(Listen(null, track))))

    private suspend fun submit(submission: Submission) {
        val t = token ?: throw IllegalStateException("ListenBrainz isn't connected")
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(Submission.serializer(), submission)
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$Api/1/submit-listens")
                .header("Authorization", "Token $t")
                .post(body)
                .build()
            http.newCall(req).execute().use { response ->
                if (response.code == 401) disconnect() // token revoked
                if (!response.isSuccessful) throw IOException("ListenBrainz HTTP ${response.code}")
            }
        }
    }

    companion object {
        private const val Api = "https://api.listenbrainz.org"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "user"

        /** Where users find their token. */
        const val SettingsUrl = "https://listenbrainz.org/settings/"
    }
}
