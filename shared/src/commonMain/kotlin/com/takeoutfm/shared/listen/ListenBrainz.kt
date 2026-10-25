package com.takeoutfm.shared.listen

import com.takeoutfm.shared.api.httpEngine
import com.takeoutfm.shared.store.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * The ListenBrainz account on this device: the user token (in [store], which must be encrypted
 * like the Takeout session; null keeps it in memory) and the ListenBrainz API calls that use it.
 */
class ListenBrainzAccount internal constructor(
    private val store: KeyValueStore?,
    engine: HttpClientEngine,
) {
    constructor(store: KeyValueStore?) : this(store, httpEngine().create())

    private val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private var memoryToken: String? = null

    private val _user = MutableStateFlow(store?.getString(KEY_USER)?.let { ListenBrainzUser(it) })
    val user: StateFlow<ListenBrainzUser?> = _user.asStateFlow()

    private val token: String? get() = store?.getString(KEY_TOKEN) ?: memoryToken

    /**
     * Checks [token] with ListenBrainz and, if it's valid, saves it; returns the user, or null
     * for a token ListenBrainz doesn't accept. Throws on network errors.
     */
    suspend fun connect(token: String): ListenBrainzUser? {
        val clean = token.trim()
        if (clean.isEmpty()) return null
        val response = http.get("$Api/1/validate-token") {
            header(HttpHeaders.Authorization, "Token $clean")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess() && response.status.value != 401) {
            throw IOException("ListenBrainz HTTP ${response.status.value}")
        }
        val check = runCatching { json.decodeFromString(TokenCheck.serializer(), body) }
            .getOrDefault(TokenCheck())
        if (!check.valid) return null
        val user = ListenBrainzUser(check.userName)
        if (store != null) {
            store.putStrings(mapOf(KEY_TOKEN to clean, KEY_USER to user.name))
        } else {
            memoryToken = clean
        }
        _user.value = user
        return user
    }

    fun disconnect() {
        store?.remove(KEY_TOKEN, KEY_USER)
        memoryToken = null
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
        val response = http.post("$Api/1/submit-listens") {
            header(HttpHeaders.Authorization, "Token $t")
            setBody(
                TextContent(
                    json.encodeToString(Submission.serializer(), submission),
                    ContentType.Application.Json,
                )
            )
        }
        if (response.status.value == 401) disconnect() // token revoked
        if (!response.status.isSuccess()) {
            throw IOException("ListenBrainz HTTP ${response.status.value}")
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
