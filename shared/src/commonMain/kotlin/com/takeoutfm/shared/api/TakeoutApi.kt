package com.takeoutfm.shared.api

import com.takeoutfm.shared.model.ApiAccessCode
import com.takeoutfm.shared.model.ApiActivityEvents
import com.takeoutfm.shared.model.ApiArtistView
import com.takeoutfm.shared.model.ApiArtistsView
import com.takeoutfm.shared.model.ApiFavoriteView
import com.takeoutfm.shared.model.ApiGenreView
import com.takeoutfm.shared.model.ApiHomeView
import com.takeoutfm.shared.model.ApiIndexView
import com.takeoutfm.shared.model.ApiLogin
import com.takeoutfm.shared.model.ApiMovieView
import com.takeoutfm.shared.model.ApiMoviesView
import com.takeoutfm.shared.model.ApiOffsets
import com.takeoutfm.shared.model.ApiPlaylist
import com.takeoutfm.shared.model.ApiPlaylistsView
import com.takeoutfm.shared.model.ApiPodcastSeriesView
import com.takeoutfm.shared.model.ApiPodcastsView
import com.takeoutfm.shared.model.ApiPopularView
import com.takeoutfm.shared.model.ApiProfileView
import com.takeoutfm.shared.model.ApiRadioView
import com.takeoutfm.shared.model.ApiRefreshTokens
import com.takeoutfm.shared.model.ApiReleaseView
import com.takeoutfm.shared.model.ApiSearchView
import com.takeoutfm.shared.model.ApiTokens
import com.takeoutfm.shared.model.ApiTrackHistoryView
import com.takeoutfm.shared.model.ApiTrackStatsView
import com.takeoutfm.shared.model.ApiTvEpisodeView
import com.takeoutfm.shared.model.ApiTvListView
import com.takeoutfm.shared.model.ApiTvSeriesView
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** A Takeout server response other than 2xx. */
class HttpException(val code: Int) : IOException("HTTP $code")

/** The platform's HTTP engine (OkHttp on Android, Darwin on Apple). */
internal expect fun httpEngine(): HttpClientEngineFactory<*>

/**
 * Thin client for the Takeout server API. Auth: bearer access token, refreshed on 401.
 * [userAgent] names the app, e.g. "TakeoutFM-TV/1.0 (takeoutfm.com; Android 14)".
 */
class TakeoutApi internal constructor(
    private val session: TakeoutSession,
    private val userAgent: String,
    engine: HttpClientEngine,
) {
    constructor(session: TakeoutSession, userAgent: String) :
        this(session, userAgent, httpEngine().create())

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    private val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    private val refreshLock = Mutex()

    /** JSON decoding off the main thread; /api/movies for a big library is large. */
    private suspend fun <T> decode(serializer: KSerializer<T>, text: String): T =
        withContext(Dispatchers.Default) { json.decodeFromString(serializer, text) }

    /** The response body; throws [HttpException] for non-2xx statuses. */
    private suspend fun execute(
        url: String,
        method: HttpMethod = HttpMethod.Get,
        bearer: String? = null,
        body: String? = null,
    ): String {
        val response = http.request(url) {
            this.method = method
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, userAgent)
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw HttpException(response.status.value)
        return text
    }

    // ---- Authentication ----

    /**
     * Signs in with a user name and password (and the account's [passcode], when it has
     * two-factor auth); returns the tokens, or null when the server rejects them. Throws on
     * network errors.
     */
    suspend fun login(endpoint: String, user: String, password: String, passcode: String?): ApiTokens? {
        val body = json.encodeToString(
            ApiLogin.serializer(),
            ApiLogin(user, password, passcode?.trim()?.ifEmpty { null }),
        )
        return try {
            val text = execute("$endpoint/api/token", HttpMethod.Post, body = body)
            decode(ApiTokens.serializer(), text).takeIf { it.isValid() }
        } catch (e: HttpException) {
            if (e.code == 401 || e.code == 403) null else throw e
        }
    }

    // ---- Device-code flow ----

    suspend fun requestCode(endpoint: String): ApiAccessCode =
        decode(ApiAccessCode.serializer(), execute("$endpoint/api/code"))

    /** Returns tokens once the user has approved the code, or null if not approved yet. */
    suspend fun checkCode(endpoint: String, code: ApiAccessCode): ApiTokens? {
        val body = json.encodeToString(ApiAccessCode.serializer(), code)
        return try {
            val text = execute("$endpoint/api/code", HttpMethod.Post, code.accessToken, body)
            decode(ApiTokens.serializer(), text).takeIf { it.isValid() }
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
                val text = execute("${state.endpoint}/api/token", bearer = tokens.refreshToken)
                val result = decode(ApiRefreshTokens.serializer(), text)
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
        method: HttpMethod = HttpMethod.Get,
        body: String? = null,
    ): String {
        val state = session.state.value
        val token = state.tokens?.accessToken ?: throw IllegalStateException("Not signed in")
        return try {
            execute(state.endpoint + path, method, token, body)
        } catch (e: HttpException) {
            if (e.code == 401 && refreshTokens(token)) {
                val fresh = session.state.value.tokens?.accessToken
                    ?: throw IllegalStateException("Not signed in")
                execute(state.endpoint + path, method, fresh, body)
            } else {
                throw e
            }
        }
    }

    private suspend fun getText(path: String): String = authed(path)

    // ---- Endpoints ----

    suspend fun index(): ApiIndexView =
        decode(ApiIndexView.serializer(), getText("/api/index"))

    /**
     * [path] is "movies/<imid>", "shows/<tvid>", "artists/<arid>" or "tracks/<etag>"; PUT adds
     * the favorite, DELETE removes it.
     */
    suspend fun setFavorite(path: String, favorite: Boolean) {
        authed("/api/favorite/$path", if (favorite) HttpMethod.Put else HttpMethod.Delete)
    }

    suspend fun favorites(): ApiFavoriteView =
        decode(ApiFavoriteView.serializer(), getText("/api/favorite"))

    suspend fun home(): ApiHomeView =
        decode(ApiHomeView.serializer(), getText("/api/home"))

    suspend fun movies(): ApiMoviesView =
        decode(ApiMoviesView.serializer(), getText("/api/movies"))

    suspend fun movie(id: Int): ApiMovieView =
        decode(ApiMovieView.serializer(), getText("/api/movies/$id"))

    suspend fun genre(name: String): ApiGenreView = decode(
        ApiGenreView.serializer(), getText("/api/movie-genres/${name.encodeURLParameter()}")
    )

    /** Recently added series and episodes. */
    suspend fun tvList(): ApiTvListView =
        decode(ApiTvListView.serializer(), getText("/api/tv"))

    suspend fun tvShows(): ApiTvListView =
        decode(ApiTvListView.serializer(), getText("/api/tv/series"))

    suspend fun tvSeries(id: Int): ApiTvSeriesView =
        decode(ApiTvSeriesView.serializer(), getText("/api/tv/series/$id"))

    suspend fun tvEpisode(id: Int): ApiTvEpisodeView =
        decode(ApiTvEpisodeView.serializer(), getText("/api/tv/episodes/$id"))

    /** A person and their movie and TV credits; [peid] is the TMDB person id. */
    suspend fun profile(peid: Int): ApiProfileView =
        decode(ApiProfileView.serializer(), getText("/api/profiles/$peid"))

    suspend fun search(query: String): ApiSearchView = decode(
        ApiSearchView.serializer(), getText("/api/search?q=${query.encodeURLParameter()}")
    )

    // ---- Music ----

    suspend fun artists(): ApiArtistsView =
        decode(ApiArtistsView.serializer(), getText("/api/artists"))

    suspend fun artist(id: Int): ApiArtistView =
        decode(ApiArtistView.serializer(), getText("/api/artists/$id"))

    suspend fun artistPopular(id: Int): ApiPopularView =
        decode(ApiPopularView.serializer(), getText("/api/artists/$id/popular"))

    suspend fun release(id: Int): ApiReleaseView =
        decode(ApiReleaseView.serializer(), getText("/api/releases/$id"))

    /** The user's radio stations, grouped by kind. */
    suspend fun radio(): ApiRadioView =
        decode(ApiRadioView.serializer(), getText("/api/radio"))

    /** The user's playlists (names and track counts). */
    suspend fun playlists(): ApiPlaylistsView =
        decode(ApiPlaylistsView.serializer(), getText("/api/playlists"))

    /**
     * A spiff playlist for any "play this" [path], e.g. "/api/releases/12/playlist" or
     * "/api/artists/3/shuffle/playlist".
     */
    suspend fun playlist(path: String): ApiPlaylist =
        decode(ApiPlaylist.serializer(), getText(path))

    /** Play counts by artist, release and track for an [interval] like "recent" or "lastweek". */
    suspend fun trackStats(interval: String): ApiTrackStatsView =
        decode(ApiTrackStatsView.serializer(), getText("/api/activity/tracks/$interval/stats"))

    /** The user's listening history (Takeout activity), newest first. */
    suspend fun recentTracks(): ApiTrackHistoryView =
        decode(ApiTrackHistoryView.serializer(), getText("/api/activity/tracks/recent"))

    // ---- Podcasts ----

    suspend fun podcasts(): ApiPodcastsView =
        decode(ApiPodcastsView.serializer(), getText("/api/podcasts"))

    suspend fun subscribedPodcasts(): ApiPodcastsView =
        decode(ApiPodcastsView.serializer(), getText("/api/podcasts/subscribed"))

    /** A podcast and its latest episodes (as many as the server is configured to list). */
    suspend fun podcastSeries(id: Int): ApiPodcastSeriesView =
        decode(ApiPodcastSeriesView.serializer(), getText("/api/series/$id"))

    suspend fun setSubscribed(seriesId: Int, subscribed: Boolean) {
        authed("/api/series/$seriesId/subscribed", if (subscribed) HttpMethod.Put else HttpMethod.Delete)
    }

    /** Records plays in the user's Takeout activity (listening stats). */
    suspend fun postActivity(events: ApiActivityEvents) {
        authed(
            "/api/activity", HttpMethod.Post,
            json.encodeToString(ApiActivityEvents.serializer(), events),
        )
    }

    /** All of the user's watch offsets. */
    suspend fun progress(): ApiOffsets =
        decode(ApiOffsets.serializer(), getText("/api/progress"))

    suspend fun updateProgress(offsets: ApiOffsets) {
        authed(
            "/api/progress", HttpMethod.Post,
            json.encodeToString(ApiOffsets.serializer(), offsets),
        )
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
