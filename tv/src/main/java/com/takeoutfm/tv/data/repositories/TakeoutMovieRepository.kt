package com.takeoutfm.tv.data.repositories

import com.takeoutfm.shared.model.ApiCast
import com.takeoutfm.shared.model.ApiEpisode
import com.takeoutfm.shared.model.ApiMovie
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.model.ApiPerson
import com.takeoutfm.shared.model.ApiSeries
import com.takeoutfm.shared.model.ApiTvListView
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.progress.instant
import com.takeoutfm.tv.data.entities.CreditKind
import com.takeoutfm.tv.data.entities.Credits
import com.takeoutfm.tv.data.entities.Episode
import com.takeoutfm.tv.data.entities.Movie
import com.takeoutfm.tv.data.entities.MovieCast
import com.takeoutfm.tv.data.entities.MovieCategory
import com.takeoutfm.tv.data.entities.MovieCategoryDetails
import com.takeoutfm.tv.data.entities.MovieCategoryList
import com.takeoutfm.tv.data.entities.MovieDetails
import com.takeoutfm.tv.data.entities.MovieList
import com.takeoutfm.tv.data.entities.MovieReviewsAndRatings
import com.takeoutfm.tv.data.entities.PersonDetails
import com.takeoutfm.tv.data.entities.Recommendation
import com.takeoutfm.tv.data.entities.SearchResults
import com.takeoutfm.tv.data.entities.Season
import com.takeoutfm.tv.data.takeout.LibraryIndex
import com.takeoutfm.tv.data.takeout.TakeoutApi
import com.takeoutfm.tv.data.takeout.TakeoutLibrary
import com.takeoutfm.tv.data.takeout.TakeoutProgress
import com.takeoutfm.tv.data.takeout.TakeoutSession
import com.takeoutfm.tv.data.takeout.TakeoutUrls
import com.takeoutfm.shared.util.listFlow
import com.takeoutfm.shared.util.sortKey
import com.takeoutfm.shared.util.sortedByTitle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [MovieRepository] backed by a Takeout server.
 *
 * Ids are prefixed by kind so one id space covers everything JetStream's screens navigate with:
 * movies are "m-<id>", TV series are "s-<id>", TV episodes are "e-<id>".
 *
 * List items carry an empty videoUri; the stream location is only resolved in [getMovieDetails],
 * which is what the details and player screens call.
 */
@Singleton
class TakeoutMovieRepository @Inject constructor(
    private val api: TakeoutApi,
    private val session: TakeoutSession,
    private val progress: TakeoutProgress,
    private val library: TakeoutLibrary,
    private val urls: TakeoutUrls,
) : MovieRepository {

    // ---- tiny in-memory TTL cache so one Home load doesn't hit /api/home four times ----

    private class CacheEntry(val at: Long, val value: Any)

    private val cache = HashMap<String, CacheEntry>()

    /** Loads in progress, so callers asking for the same thing at once share one request. */
    private val inFlight = HashMap<String, Deferred<Any>>()
    private val cacheLock = Mutex()

    /** Shared loads outlive any one caller, so a caller leaving doesn't cancel it for the rest. */
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun <T : Any> cached(
        key: String,
        ttlMs: Long = 60_000,
        load: suspend () -> T,
    ): T {
        // Scope the cache to the signed-in user so a new sign-in never sees old data.
        val scopedKey = "${session.tokens?.mediaToken}:$key"
        val deferred = cacheLock.withLock {
            val hit = cache[scopedKey]
            if (hit != null && System.currentTimeMillis() - hit.at < ttlMs) {
                @Suppress("UNCHECKED_CAST")
                return hit.value as T
            }
            inFlight.getOrPut(scopedKey) {
                loadScope.async {
                    try {
                        val value: Any = load()
                        cacheLock.withLock {
                            cache[scopedKey] = CacheEntry(System.currentTimeMillis(), value)
                        }
                        value
                    } finally {
                        cacheLock.withLock { inFlight.remove(scopedKey) }
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return deferred.await() as T
    }

    private suspend fun home() = library.home()

    /** The library index, fetching it first if there isn't one yet. Null if that fails. */
    private suspend fun index(): LibraryIndex? {
        if (library.index.value == null) library.refresh()
        return library.index.value
    }

    private val endpoint get() = session.endpoint

    // ---- Home ----

    override fun getFeaturedMovies(): Flow<MovieList> = listFlow {
        val h = home()
        (h.newMovies ?: h.addedMovies).orEmpty().take(8).map { it.toMovie(endpoint, long = true) }
    }

    /** Shown as "Recently Added". */
    override fun getTrendingMovies(): Flow<MovieList> = listFlow {
        val h = home()
        (h.addedMovies ?: h.newMovies).orEmpty().map { it.toMovie(endpoint) }
    }

    /** Shown as "Recommended". */
    override fun getTop10Movies(): Flow<MovieList> = listFlow {
        val h = home()
        val recommended = h.recommendMovies?.firstOrNull()?.movies
        (recommended ?: h.addedMovies).orEmpty().take(10).map { it.toMovie(endpoint) }
    }

    /** Shown as "New Releases". */
    override fun getNowPlayingMovies(): Flow<MovieList> = listFlow {
        val h = home()
        (h.newMovies ?: h.addedMovies).orEmpty().map { it.toMovie(endpoint) }
    }

    // ---- Categories (genres) ----

    /** The genres that actually occur in this server's movie library, A–Z. */
    private suspend fun genres(): List<String> =
        index()?.movieGenres.orEmpty().distinct().sortedByTitle { it }

    override fun getMovieCategories(): Flow<MovieCategoryList> = flow {
        val genres = try {
            genres()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        emit(genres.map { MovieCategory(id = slug(it), name = it) })
    }

    override suspend fun getMovieCategoryDetails(categoryId: String): MovieCategoryDetails =
        withContext(Dispatchers.Default) { categoryDetails(categoryId) }

    private suspend fun categoryDetails(categoryId: String): MovieCategoryDetails {
        val name = try {
            genres().firstOrNull { slug(it) == categoryId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: categoryId
        val movies = try {
            cached("genre:$name", ttlMs = 300_000) { api.genre(name) }
                .movies.orEmpty().sortedAZ().map { it.toMovie(endpoint) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return MovieCategoryDetails(id = slug(name), name = name, movies = movies)
    }

    // ---- Details ----

    /** Throws if the server can't be reached or the title is gone; screens show an error. */
    override suspend fun getMovieDetails(movieId: String): MovieDetails =
        if (movieId.startsWith("s-")) {
            seriesDetails(movieId.removePrefix("s-").toInt())
        } else if (movieId.startsWith("e-")) {
            episodeDetails(movieId.removePrefix("e-").toInt())
        } else {
            movieDetails(movieId.removePrefix("m-").toInt())
        }

    private suspend fun movieDetails(id: Int): MovieDetails {
        val view = api.movie(id)
        val m = view.movie
        return MovieDetails(
            id = "m-${m.id}",
            videoUri = urls.absolute(view.location),
            subtitleUri = null,
            posterUri = urls.tmdbImage("w1280", m.backdropPath.ifEmpty { m.posterPath }),
            name = m.title,
            description = m.overview.ifEmpty { m.tagline },
            pgRating = m.rating.ifEmpty { "NR" },
            releaseDate = m.date.take(4),
            categories = view.genres.orEmpty(),
            duration = durationOf(m.runtime),
            director = view.directing.names(),
            screenplay = view.writing.names(),
            music = view.crew.orEmpty()
                .filter { it.job.contains("Composer", true) || it.job.contains("Music", true) }
                .map { it.person.name }.distinct().joinToString(", ").ifEmpty { Dash },
            castAndCrew = castAndCrew(view.cast, view.directing),
            status = "Available",
            originalLanguage = Dash,
            budget = formatDollars(m.budget),
            revenue = formatDollars(m.revenue),
            similarMovies = view.other.orEmpty().map { it.toMovie(endpoint) },
            reviewsAndRatings = ratings(m.voteAverage, m.voteCount),
            etag = m.etag,
            isFavorite = view.isFavorite,
            favoritePath = m.imid.ifEmpty { null }?.let { "movies/$it" },
            tagline = m.tagline,
        )
    }

    /** Series details list every episode by season; Play starts the first one. */
    private suspend fun seriesDetails(id: Int): MovieDetails {
        val view = api.tvSeries(id)
        val s = view.series
        val episodes = view.episodes.orEmpty()
        val first = nextEpisode(episodes)
        val location = if (first == null) {
            ""
        } else {
            try {
                api.tvEpisode(first.id).location
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }
        val seasons = episodes
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .groupBy { it.season }
            // Specials (season 0) go last.
            .toSortedMap(compareBy({ it == 0 }, { it }))
            .map { (number, list) -> Season(number, list.map { it.toEpisode() }) }
        return MovieDetails(
            id = "s-${s.id}",
            videoUri = urls.absolute(location),
            subtitleUri = null,
            posterUri = urls.tmdbImage("w1280", s.backdropPath.ifEmpty { s.posterPath }),
            name = s.name,
            description = s.overview,
            pgRating = s.rating.ifEmpty { "NR" },
            releaseDate = s.date.take(4),
            categories = view.genres.orEmpty(),
            duration = "${s.seasonCount} seasons · ${s.episodeCount} episodes",
            director = view.directing.names(),
            screenplay = view.writing.names(),
            music = Dash,
            castAndCrew = castAndCrew(view.cast, view.directing),
            status = "Available",
            originalLanguage = Dash,
            budget = Dash,
            revenue = Dash,
            similarMovies = emptyList(),
            reviewsAndRatings = ratings(s.voteAverage, s.voteCount),
            etag = first?.etag.orEmpty(),
            seasons = seasons,
            playTarget = first?.let { "S${it.season}E${it.episode}" },
            tagline = s.tagline,
            isFavorite = view.isFavorite,
            favoritePath = s.tvid.takeIf { it > 0 }?.let { "shows/$it" },
        )
    }

    /**
     * The episode Play should start: the most recently watched episode if it's unfinished, else
     * the one after it; with no progress (or after the last one), the first. Specials (season 0)
     * only play when there are no regular episodes.
     */
    private suspend fun nextEpisode(episodes: List<ApiEpisode>): ApiEpisode? {
        val ordered = playOrder(episodes)
        if (ordered.isEmpty()) return null
        val offsets = try {
            progress.latest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        val lastIndex = ordered.indices
            .filter { offsets[ordered[it].etag] != null }
            .maxByOrNull { offsets.getValue(ordered[it].etag).instant() }
            ?: return ordered.first()
        val last = offsets.getValue(ordered[lastIndex].etag)
        return if (ProgressRules.isFinished(last)) {
            ordered.getOrNull(lastIndex + 1) ?: ordered.first()
        } else {
            ordered[lastIndex]
        }
    }

    /** Seasons in order, then episodes; specials (season 0) only when there's nothing else. */
    private fun playOrder(episodes: List<ApiEpisode>): List<ApiEpisode> =
        episodes.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
            .ifEmpty { episodes.sortedBy { it.episode } }

    private suspend fun episodeDetails(id: Int): MovieDetails {
        val view = api.tvEpisode(id)
        val e = view.episode
        val s = view.series
        // Neighbours for the player's previous/next episode buttons; none if the series won't load.
        val order = try {
            playOrder(api.tvSeries(s.id).episodes.orEmpty())
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            emptyList()
        }
        val index = order.indexOfFirst { it.id == e.id }
        return MovieDetails(
            id = "e-${e.id}",
            videoUri = urls.absolute(view.location),
            subtitleUri = null,
            posterUri = urls.tmdbImage("w1280", e.stillPath.ifEmpty { s.backdropPath }),
            name = "${s.name} · S${e.season}E${e.episode} ${e.name}".trim(),
            description = e.overview,
            pgRating = s.rating.ifEmpty { "NR" },
            releaseDate = e.date.take(4),
            categories = emptyList(),
            duration = durationOf(e.runtime),
            director = Dash,
            screenplay = Dash,
            music = Dash,
            castAndCrew = emptyList(),
            status = "Available",
            originalLanguage = Dash,
            budget = Dash,
            revenue = Dash,
            similarMovies = emptyList(),
            reviewsAndRatings = emptyList(),
            etag = e.etag,
            // Episodes have none of their own; use the series'.
            tagline = s.tagline,
            previousEpisodeId = order.getOrNull(index - 1)?.takeIf { index > 0 }?.let { "e-${it.id}" },
            nextEpisodeId = order.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { "e-${it.id}" },
        )
    }

    // ---- Continue Watching ----
    // Progress is keyed by media file ETag only, so titles are found by matching ETags against
    // the (cached) movie and TV libraries.

    /**
     * Movies and TV episodes started within the last [days] days and not finished, most recently
     * watched first. Cards are landscape: movie backdrops, episode stills.
     */
    fun getContinueWatching(days: Int): Flow<MovieList> = listFlow {
        val offsets = progress.inProgress(days)
        if (offsets.isEmpty()) return@listFlow emptyList()
        // Only fetch the libraries the server says it has.
        val index = index()
        val movies = if (index?.hasMovies != false) {
            cached("movies") { api.movies() }.movies.orEmpty()
                .filter { it.etag.isNotEmpty() }.associateBy { it.etag }
        } else {
            emptyMap()
        }
        val tv = if (index?.hasShows != false) cached("tv-recent") { api.tvList() } else ApiTvListView()
        val episodes = tv.episodes.orEmpty().filter { it.etag.isNotEmpty() }.associateBy { it.etag }
        val seriesByTvid = tv.series.orEmpty().associateBy { it.tvid }
        offsets.mapNotNull { o ->
            val m = movies[o.etag]
            if (m != null) {
                return@mapNotNull m.toMovie(endpoint, long = true).copy(progress = fraction(o, m.runtime))
            }
            val e = episodes[o.etag] ?: return@mapNotNull null
            e.toEpisodeCard(seriesByTvid[e.tvid]).copy(progress = fraction(o, e.runtime))
        }
    }

    /** A landscape card for an episode: its still, titled "Series · S1E2 Name". */
    private fun ApiEpisode.toEpisodeCard(series: ApiSeries?) = Movie(
        id = "e-$id",
        videoUri = "",
        subtitleUri = null,
        posterUri = urls.tmdbImage("w300", stillPath)
            .ifEmpty { urls.tmdbImage("w780", series?.backdropPath.orEmpty()) },
        name = listOfNotNull(series?.name, "S${season}E$episode $name".trim()).joinToString(" · "),
        description = overview,
    )

    /** Offset over duration, falling back to the runtime (minutes) when the duration is unknown. */
    private fun fraction(o: ApiOffset, runtimeMinutes: Int): Float? {
        val total = if (o.duration > 0) o.duration else runtimeMinutes * 60
        return if (total > 0) (o.offset.toFloat() / total).coerceIn(0f, 1f) else null
    }

    // ---- Recommendations ----

    /**
     * The server's current recommendation sets (they depend on the date), each A–Z.
     * Empty when there are none or the server can't be reached.
     */
    /** [long] gives landscape backdrops instead of posters, for wide cards. */
    /**
     * The current recommendation sets, following the library index (empty when the server has
     * none). Starts from the saved index, so tabs can appear before anything is fetched.
     */
    fun getRecommendations(long: Boolean = false): Flow<List<Recommendation>> =
        library.index
            .map { index ->
                index?.recommendations.orEmpty()
                    .takeIf { index?.hasRecommendations == true }.orEmpty()
                    .filter { it.name.isNotBlank() && it.movies.isNotEmpty() }
                    .map { r ->
                        Recommendation(r.name, r.movies.sortedAZ().map { it.toMovie(endpoint, long) })
                    }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // ---- Favorites ----

    suspend fun setFavorite(favoritePath: String, favorite: Boolean) =
        api.setFavorite(favoritePath, favorite)

    // ---- People ----

    /** [personId] is the TMDB person id, as carried by [MovieCast.id]. */
    suspend fun getPersonDetails(personId: String): PersonDetails {
        val view = api.profile(personId.toInt())
        val p = view.person
        val born = p.birthday.year()
        val died = p.deathday.year()
        val years = when {
            born != null && died != null -> "$born–$died"
            born != null -> "Born $born"
            else -> null
        }
        val credits = listOf(
            CreditKind.MoviesActing to view.movies.starring.toMovies(),
            CreditKind.MoviesDirecting to view.movies.directing.toMovies(),
            CreditKind.MoviesWriting to view.movies.writing.toMovies(),
            CreditKind.ShowsActing to view.shows.starring.toShows(),
            CreditKind.ShowsDirecting to view.shows.directing.toShows(),
            CreditKind.ShowsWriting to view.shows.writing.toShows(),
        ).filter { it.second.isNotEmpty() }.map { (kind, titles) -> Credits(kind, titles) }
        return PersonDetails(
            id = p.peid.toString(),
            name = p.name,
            photoUri = p.profilePath?.let { urls.tmdbImage("w342", it) }.orEmpty(),
            lifeSummary = listOfNotNull(years, p.birthplace.ifEmpty { null }).joinToString(" · "),
            bio = p.bio,
            credits = credits,
        )
    }

    /** Newest first, like a filmography. */
    private fun List<ApiMovie>?.toMovies(): MovieList =
        orEmpty().distinctBy { it.id }.sortedByDescending { it.date }.map { it.toMovie(endpoint) }

    private fun List<ApiSeries>?.toShows(): MovieList =
        orEmpty().distinctBy { it.id }.sortedByDescending { it.date }.map { it.toMovie(endpoint) }

    /** Go's zero time ("0001-01-01T...") means unknown. */
    private fun String.year(): Int? = take(4).toIntOrNull()?.takeIf { it > 1000 }

    // ---- Search ----

    /**
     * Movies and TV episodes come from /api/search. The server doesn't search TV series, so those
     * are matched here against the (cached) series list by name.
     */
    suspend fun search(query: String): SearchResults = withContext(Dispatchers.Default) { doSearch(query) }

    private suspend fun doSearch(query: String): SearchResults {
        val q = sortKey(query)
        if (q.isEmpty()) return SearchResults()
        return try {
            val view = api.search(allWordsQuery(query))
            val tv = if (index()?.hasShows != false) cached("tv-recent") { api.tvList() } else ApiTvListView()
            val seriesByTvid = tv.series.orEmpty().associateBy { it.tvid }
            SearchResults(
                // Titles containing the whole phrase first, then the rest, each A–Z.
                movies = view.movies.orEmpty().sortedAZ()
                    .sortedBy { q !in sortKey(it.title) } // stable: A–Z within each group
                    .map { it.toMovie(endpoint) },
                shows = tv.series.orEmpty()
                    .filter { q in sortKey(it.name) }
                    .sortedByTitle { it.sortName.ifEmpty { it.name } }
                    .map { it.toMovie(endpoint) },
                episodes = view.tvEpisodes.orEmpty().map { it.toEpisodeCard(seriesByTvid[it.tvid]) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SearchResults()
        }
    }

    override suspend fun searchMovies(query: String): MovieList = try {
        api.search(query).movies.orEmpty().map { it.toMovie(endpoint) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    // ---- Movies / TV tabs ----

    override fun getMoviesWithLongThumbnail(): Flow<MovieList> = listFlow {
        cached("movies") { api.movies() }.movies.orEmpty().map { it.toMovie(endpoint, long = true) }
    }

    /** The whole movie library, A–Z. */
    override fun getMovies(): Flow<MovieList> = listFlow {
        cached("movies") { api.movies() }.movies.orEmpty().sortedAZ().map { it.toMovie(endpoint) }
    }

    override fun getPopularFilmsThisWeek(): Flow<MovieList> = listFlow {
        val h = home()
        (h.recommendMovies?.getOrNull(1)?.movies ?: h.addedMovies).orEmpty()
            .map { it.toMovie(endpoint) }
    }

    /** Every TV series, A–Z. */
    override fun getTVShows(): Flow<MovieList> = listFlow {
        cached("tv-shows") { api.tvShows() }.series.orEmpty()
            .sortedByTitle { it.sortName.ifEmpty { it.name } }
            .map { it.toMovie(endpoint) }
    }

    override fun getBingeWatchDramas(): Flow<MovieList> = listFlow {
        cached("tv-recent") { api.tvList() }.series.orEmpty().map { it.toMovie(endpoint, long = true) }
    }

    /**
     * Favorite movies and TV shows together, A–Z. Not cached: favorites can change from the
     * other Takeout apps at any time.
     */
    override fun getFavouriteMovies(): Flow<MovieList> = listFlow {
        val view = api.favorites()
        val movies = view.movies.orEmpty().map { it.sortTitle.ifEmpty { it.title } to it.toMovie(endpoint) }
        val shows = view.shows.orEmpty().map { it.sortName.ifEmpty { it.name } to it.toMovie(endpoint) }
        (movies + shows).sortedByTitle { it.first }.map { it.second }
    }

    // ---- Helpers ----

    /** By the server's sort title (e.g. without a leading "The"), falling back to the title. */
    private fun List<ApiMovie>.sortedAZ(): List<ApiMovie> =
        sortedByTitle { it.sortTitle.ifEmpty { it.title } }

    private fun ApiMovie.toMovie(endpoint: String, long: Boolean = false): Movie {
        val poster = if (long) {
            urls.tmdbImage("w1280", backdropPath.ifEmpty { posterPath })
        } else {
            urls.tmdbImage("w342", posterPath.ifEmpty { backdropPath })
        }
        return Movie(
            id = "m-$id",
            videoUri = "",
            subtitleUri = null,
            posterUri = poster,
            name = title,
            description = overview.ifEmpty { tagline },
        )
    }

    private fun ApiSeries.toMovie(endpoint: String, long: Boolean = false): Movie {
        val poster = if (long) {
            urls.tmdbImage("w1280", backdropPath.ifEmpty { posterPath })
        } else {
            urls.tmdbImage("w342", posterPath.ifEmpty { backdropPath })
        }
        return Movie(
            id = "s-$id",
            videoUri = "",
            subtitleUri = null,
            posterUri = poster,
            name = name,
            description = overview,
        )
    }

    private fun ApiEpisode.toEpisode() = Episode(
        id = "e-$id",
        season = season,
        episode = episode,
        name = name,
        description = overview,
        stillUri = urls.tmdbImage("w300", stillPath),
        duration = durationOf(runtime),
    )

    private fun ApiCast.toMovieCast() = MovieCast(
        id = peid.toString(),
        characterName = character,
        realName = person.name,
        avatarUrl = person.profilePath?.let { urls.tmdbImage("w185", it) }.orEmpty(),
    )

    private fun castAndCrew(cast: List<ApiCast>?, directing: List<ApiPerson>?) =
        withDirectorsFirst(
            directors = directing.orEmpty().filter { it.peid > 0 }.map {
                MovieCast(
                    id = it.peid.toString(),
                    characterName = "",
                    realName = it.name,
                    avatarUrl = it.profilePath?.let { path -> urls.tmdbImage("w185", path) }.orEmpty(),
                )
            },
            cast = cast.orEmpty().map { it.toMovieCast() },
        )

    private fun List<ApiPerson>?.names(): String =
        this.orEmpty().map { it.name }.distinct().joinToString(", ").ifEmpty { Dash }

    private fun durationOf(minutes: Int): String =
        if (minutes <= 0) Dash else "${minutes / 60}h ${minutes % 60}m"

    private fun ratings(average: Float?, count: Int?): List<MovieReviewsAndRatings> =
        if (average == null || average <= 0f) {
            emptyList()
        } else {
            listOf(
                MovieReviewsAndRatings(
                    reviewerName = "TMDB",
                    reviewerIconUri = "",
                    reviewCount = (count ?: 0).toString(),
                    reviewRating = String.format(Locale.US, "%.1f / 10", average),
                )
            )
        }

    /**
     * The server takes a Bleve query string, where "evil dead" means evil OR dead; require every
     * word instead ("+evil +dead"). Query syntax characters are dropped from what the user typed.
     */
    private fun allWordsQuery(query: String): String =
        query.replace(Regex("""[+\-=&|><!(){}\[\]^"~*?:\\/]"""), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { "+$it" }

    private fun slug(name: String) = name.lowercase(Locale.US).replace(' ', '-')

    companion object {
        const val Dash = "—"

        /** "325000" -> "$325,000"; empty, zero or unparseable (TMDB's "unknown") -> "—". */
        fun formatDollars(amount: String): String {
            val value = amount.trim().toLongOrNull()?.takeIf { it > 0 } ?: return Dash
            return "$" + String.format(Locale.US, "%,d", value)
        }

        /**
         * Cast & Crew: [directors] first, labelled "Director", then [cast]. A director who also
         * acts gets one card, "Director · as <role>", instead of a second one in the cast.
         */
        fun withDirectorsFirst(directors: List<MovieCast>, cast: List<MovieCast>): List<MovieCast> {
            val roles = cast.groupBy { it.id }
            val first = directors.distinctBy { it.id }.map { director ->
                val role = roles[director.id].orEmpty().map { it.characterName }
                    .filter { it.isNotBlank() }.distinct().joinToString(" / ")
                director.copy(characterName = if (role.isEmpty()) "Director" else "Director · as $role")
            }
            val ids = first.map { it.id }.toSet()
            return first + cast.filter { it.id !in ids }
        }
    }
}
