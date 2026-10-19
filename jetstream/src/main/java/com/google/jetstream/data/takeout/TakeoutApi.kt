package com.google.jetstream.data.takeout

import android.net.Uri
import android.os.Build
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Thin client for the Takeout server API. Auth: bearer access token, refreshed on 401. */
@Singleton
class TakeoutApi @Inject constructor(
    private val session: TakeoutSession,
) {
    class HttpException(val code: Int) : IOException("HTTP $code")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val refreshLock = Mutex()

    private val userAgent = "TakeoutFM-TV/1.0 (takeoutfm.com; Android ${Build.VERSION.RELEASE})"

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", userAgent)

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpException(response.code)
            body
        }
    }

    // ---- Authentication (device-code flow) ----

    suspend fun requestCode(endpoint: String): ApiAccessCode {
        val req = request("$endpoint/api/code").build()
        return json.decodeFromString(ApiAccessCode.serializer(), execute(req))
    }

    /** Returns tokens once the user has approved the code, or null if not approved yet. */
    suspend fun checkCode(endpoint: String, code: ApiAccessCode): ApiTokens? {
        val body = json.encodeToString(ApiAccessCode.serializer(), code)
            .toRequestBody("application/json".toMediaType())
        val req = request("$endpoint/api/code")
            .header("Authorization", "Bearer ${code.accessToken}")
            .post(body)
            .build()
        return try {
            json.decodeFromString(ApiTokens.serializer(), execute(req)).takeIf { it.isValid() }
        } catch (e: HttpException) {
            null
        }
    }

    private suspend fun refreshTokens(staleAccessToken: String?): Boolean {
        return refreshLock.withLock {
            val state = session.state.value
            val tokens = state.tokens ?: return@withLock false
            // Another request already refreshed while we waited for the lock.
            if (tokens.accessToken != staleAccessToken) return@withLock true
            try {
                val req = request("${state.endpoint}/api/token")
                    .header("Authorization", "Bearer ${tokens.refreshToken}")
                    .build()
                val result = json.decodeFromString(ApiRefreshTokens.serializer(), execute(req))
                if (result.accessToken.isNotEmpty() && result.refreshToken.isNotEmpty()) {
                    session.updateTokens(result.accessToken, result.refreshToken)
                    true
                } else {
                    false
                }
            } catch (e: HttpException) {
                if (e.code == 401) session.signOut() // refresh token no longer valid
                false
            }
        }
    }

    // ---- Authenticated requests ----

    private suspend fun authed(
        path: String,
        configure: Request.Builder.() -> Request.Builder = { this },
    ): String {
        val state = session.state.value
        val token = state.tokens?.accessToken ?: throw IllegalStateException("Not signed in")
        val req = request(state.endpoint + path)
            .header("Authorization", "Bearer $token")
            .configure()
            .build()
        return try {
            execute(req)
        } catch (e: HttpException) {
            if (e.code == 401 && refreshTokens(token)) {
                val fresh = session.state.value.tokens?.accessToken
                    ?: throw IllegalStateException("Not signed in")
                execute(
                    request(state.endpoint + path)
                        .header("Authorization", "Bearer $fresh")
                        .configure()
                        .build()
                )
            } else {
                throw e
            }
        }
    }

    private suspend fun getText(path: String): String = authed(path)

    // ---- Endpoints ----

    suspend fun index(): ApiIndexView =
        json.decodeFromString(ApiIndexView.serializer(), getText("/api/index"))

    /** [path] is "movies/<imid>" or "shows/<tvid>"; PUT adds the favorite, DELETE removes it. */
    suspend fun setFavorite(path: String, favorite: Boolean) {
        authed("/api/favorite/$path") {
            if (favorite) put(ByteArray(0).toRequestBody()) else delete()
        }
    }

    suspend fun favorites(): ApiFavoriteView =
        json.decodeFromString(ApiFavoriteView.serializer(), getText("/api/favorite"))

    suspend fun home(): ApiHomeView =
        json.decodeFromString(ApiHomeView.serializer(), getText("/api/home"))

    suspend fun movies(): ApiMoviesView =
        json.decodeFromString(ApiMoviesView.serializer(), getText("/api/movies"))

    suspend fun movie(id: Int): ApiMovieView =
        json.decodeFromString(ApiMovieView.serializer(), getText("/api/movies/$id"))

    suspend fun genre(name: String): ApiGenreView = json.decodeFromString(
        ApiGenreView.serializer(), getText("/api/movie-genres/${Uri.encode(name)}")
    )

    /** Recently added series and episodes. */
    suspend fun tvList(): ApiTvListView =
        json.decodeFromString(ApiTvListView.serializer(), getText("/api/tv"))

    suspend fun tvShows(): ApiTvListView =
        json.decodeFromString(ApiTvListView.serializer(), getText("/api/tv/series"))

    suspend fun tvSeries(id: Int): ApiTvSeriesView =
        json.decodeFromString(ApiTvSeriesView.serializer(), getText("/api/tv/series/$id"))

    suspend fun tvEpisode(id: Int): ApiTvEpisodeView =
        json.decodeFromString(ApiTvEpisodeView.serializer(), getText("/api/tv/episodes/$id"))

    /** A person and their movie and TV credits; [peid] is the TMDB person id. */
    suspend fun profile(peid: Int): ApiProfileView =
        json.decodeFromString(ApiProfileView.serializer(), getText("/api/profiles/$peid"))

    suspend fun search(query: String): ApiSearchView = json.decodeFromString(
        ApiSearchView.serializer(), getText("/api/search?q=${Uri.encode(query)}")
    )

    /** All of the user's watch offsets. */
    suspend fun progress(): ApiOffsets =
        json.decodeFromString(ApiOffsets.serializer(), getText("/api/progress"))

    suspend fun updateProgress(offsets: ApiOffsets) {
        val body = json.encodeToString(ApiOffsets.serializer(), offsets)
            .toRequestBody("application/json".toMediaType())
        authed("/api/progress") { post(body) }
    }

    companion object {
        /** "takeout.fm/" -> "https://takeout.fm" */
        fun normalizeEndpoint(raw: String): String {
            var e = raw.trim().trimEnd('/')
            if (e.isNotEmpty() && !e.startsWith("http://") && !e.startsWith("https://")) {
                e = "https://$e"
            }
            return e
        }
    }
}
