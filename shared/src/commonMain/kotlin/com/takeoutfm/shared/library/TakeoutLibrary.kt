package com.takeoutfm.shared.library

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.model.ApiHomeView
import com.takeoutfm.shared.model.ApiRecommend
import com.takeoutfm.shared.store.KeyValueStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the server's library has, from /api/index, plus the current recommendation sets. */
@Serializable
data class LibraryIndex(
    val endpoint: String,
    // Defaulted so an index saved by an older build still loads.
    val hasMusic: Boolean = false,
    val hasPlaylists: Boolean = false,
    val hasMovies: Boolean,
    val hasShows: Boolean,
    val hasRecommendations: Boolean,
    val movieGenres: List<String>,
    /** From /api/home; empty when [hasRecommendations] is false. */
    val recommendations: List<ApiRecommend>,
    val fetchedAt: Long,
)

/**
 * The library index the UI is shaped by (which tabs exist, which requests are worth making).
 *
 * The last index is saved on the device so the UI can start from it immediately; [refresh]
 * then fetches a fresh one. Callers refresh on launch/foreground, when Home is shown and after
 * sign-in. Also the single source of /api/home, so Home's rows and the recommendation tabs
 * share one request.
 */
class TakeoutLibrary(
    private val api: TakeoutApi,
    private val session: TakeoutSession,
    /** Where the last index is saved (Android: the "takeout_library" preferences). */
    private val store: KeyValueStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _index = MutableStateFlow(loadSaved())

    /** Null until the first index for this server has been fetched. */
    val index: StateFlow<LibraryIndex?> = _index.asStateFlow()

    private val refreshLock = Mutex()

    init {
        // Signing out or switching servers drops what was learned about the old library.
        scope.launch {
            session.state.collect { state ->
                val current = _index.value
                if (current != null && (!state.isLoggedIn || current.endpoint != state.endpoint)) {
                    clear()
                }
            }
        }
    }

    /**
     * Fetches the index (and, if there are recommendations, the sets from /api/home) unless the
     * current one is younger than [maxAgeMs]. Failures keep the previous index.
     */
    suspend fun refresh(maxAgeMs: Long = 0) {
        refreshLock.withLock {
            val current = _index.value
            if (current != null && now() - current.fetchedAt < maxAgeMs) return
            try {
                val view = api.index()
                val hasRecommendations = view.hasRecommendMovies ?: true
                val recommendations = if (hasRecommendations) {
                    home(maxAgeMs = 0).recommendMovies.orEmpty()
                } else {
                    emptyList()
                }
                update(
                    LibraryIndex(
                        endpoint = session.endpoint,
                        hasMusic = view.hasMusic ?: false,
                        hasPlaylists = view.hasPlaylists ?: false,
                        hasMovies = view.hasMovies ?: true,
                        hasShows = view.hasShows ?: true,
                        hasRecommendations = hasRecommendations,
                        movieGenres = view.movieGenres.orEmpty(),
                        recommendations = recommendations,
                        fetchedAt = now(),
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or server error: keep showing what we had.
            }
        }
    }

    // ---- /api/home, shared ----

    private val homeLock = Mutex()
    private var homeView: ApiHomeView? = null
    private var homeAt = 0L
    private var homeLoad: Deferred<ApiHomeView>? = null

    /** /api/home, reused for [maxAgeMs]; concurrent callers share one request. */
    suspend fun home(maxAgeMs: Long = 60_000): ApiHomeView {
        val load = homeLock.withLock {
            val cached = homeView
            if (cached != null && now() - homeAt < maxAgeMs) return cached
            homeLoad ?: scope.async {
                try {
                    val view = api.home()
                    homeLock.withLock {
                        homeView = view
                        homeAt = now()
                    }
                    // Keep the saved recommendation sets in step with what Home shows.
                    _index.value?.let { current ->
                        val sets = view.recommendMovies.orEmpty()
                        if (current.hasRecommendations && sets != current.recommendations) {
                            update(current.copy(recommendations = sets))
                        }
                    }
                    view
                } finally {
                    homeLock.withLock { homeLoad = null }
                }
            }.also { homeLoad = it }
        }
        return load.await()
    }

    // ---- Saved copy ----

    private fun update(index: LibraryIndex) {
        _index.value = index
        store.putString(KEY_INDEX, json.encodeToString(LibraryIndex.serializer(), index))
    }

    private fun clear() {
        _index.value = null
        homeView = null
        store.remove(KEY_INDEX)
    }

    private fun loadSaved(): LibraryIndex? {
        val saved = store.getString(KEY_INDEX) ?: return null
        return runCatching { json.decodeFromString(LibraryIndex.serializer(), saved) }.getOrNull()
            ?.takeIf { it.endpoint == session.endpoint }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val KEY_INDEX = "index"
    }
}
