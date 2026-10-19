package com.google.jetstream.data.repositories

import com.google.jetstream.data.entities.CreditKind
import com.google.jetstream.data.entities.Credits
import com.google.jetstream.data.entities.Episode
import com.google.jetstream.data.entities.Movie
import com.google.jetstream.data.entities.MovieCast
import com.google.jetstream.data.entities.MovieCategory
import com.google.jetstream.data.entities.MovieCategoryDetails
import com.google.jetstream.data.entities.MovieCategoryList
import com.google.jetstream.data.entities.MovieDetails
import com.google.jetstream.data.entities.MovieList
import com.google.jetstream.data.entities.MovieReviewsAndRatings
import com.google.jetstream.data.entities.PersonDetails
import com.google.jetstream.data.entities.Recommendation
import com.google.jetstream.data.entities.Season
import com.google.jetstream.data.takeout.ApiCast
import com.google.jetstream.data.takeout.ApiEpisode
import com.google.jetstream.data.takeout.ApiMovie
import com.google.jetstream.data.takeout.ApiPerson
import com.google.jetstream.data.takeout.ApiSeries
import com.google.jetstream.data.takeout.TakeoutApi
import com.google.jetstream.data.takeout.TakeoutSession
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) : MovieRepository {

    // ---- tiny in-memory TTL cache so one Home load doesn't hit /api/home four times ----

    private class CacheEntry(val at: Long, val value: Any)

    private val cache = HashMap<String, CacheEntry>()
    private val cacheLock = Mutex()

    private suspend fun <T : Any> cached(
        key: String,
        ttlMs: Long = 60_000,
        load: suspend () -> T,
    ): T {
        // Scope the cache to the signed-in user so a new sign-in never sees old data.
        val scopedKey = "${session.tokens?.mediaToken}:$key"
        cacheLock.withLock {
            val hit = cache[scopedKey]
            if (hit != null && System.currentTimeMillis() - hit.at < ttlMs) {
                @Suppress("UNCHECKED_CAST")
                return hit.value as T
            }
        }
        val value = load()
        cacheLock.withLock { cache[scopedKey] = CacheEntry(System.currentTimeMillis(), value) }
        return value
    }

    private fun <T> listFlow(block: suspend () -> List<T>): Flow<List<T>> = flow {
        val list = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        emit(list)
    }

    private suspend fun home() = cached("home") { api.home() }

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
        cached("index", ttlMs = 300_000) { api.index() }.movieGenres.orEmpty()
            .distinct().sortedBy { it.lowercase(Locale.ROOT) }

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

    override suspend fun getMovieCategoryDetails(categoryId: String): MovieCategoryDetails {
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

    override suspend fun getMovieDetails(movieId: String): MovieDetails {
        return try {
            if (movieId.startsWith("s-")) {
                seriesDetails(movieId.removePrefix("s-").toInt())
            } else if (movieId.startsWith("e-")) {
                episodeDetails(movieId.removePrefix("e-").toInt())
            } else {
                movieDetails(movieId.removePrefix("m-").toInt())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // JetStream's view models expect this call not to throw, so degrade to a stub.
            unavailableDetails(movieId)
        }
    }

    private suspend fun movieDetails(id: Int): MovieDetails {
        val view = api.movie(id)
        val m = view.movie
        return MovieDetails(
            id = "m-${m.id}",
            videoUri = absolute(view.location),
            subtitleUri = null,
            posterUri = imageUrl("w1280", m.backdropPath.ifEmpty { m.posterPath }),
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
            castAndCrew = view.cast.orEmpty().map { it.toMovieCast() },
            status = "Available",
            originalLanguage = Dash,
            budget = m.budget.ifEmpty { Dash },
            revenue = m.revenue.ifEmpty { Dash },
            similarMovies = view.other.orEmpty().map { it.toMovie(endpoint) },
            reviewsAndRatings = ratings(m.voteAverage, m.voteCount),
            etag = m.etag,
        )
    }

    /** Series details list every episode by season; Play starts the first one. */
    private suspend fun seriesDetails(id: Int): MovieDetails {
        val view = api.tvSeries(id)
        val s = view.series
        val episodes = view.episodes.orEmpty()
        val first = episodes.filter { it.season > 0 }
            .minWithOrNull(compareBy({ it.season }, { it.episode }))
            ?: episodes.firstOrNull()
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
            videoUri = absolute(location),
            subtitleUri = null,
            posterUri = imageUrl("w1280", s.backdropPath.ifEmpty { s.posterPath }),
            name = s.name,
            description = s.overview,
            pgRating = s.rating.ifEmpty { "NR" },
            releaseDate = s.date.take(4),
            categories = view.genres.orEmpty(),
            duration = "${s.seasonCount} seasons · ${s.episodeCount} episodes",
            director = view.directing.names(),
            screenplay = view.writing.names(),
            music = Dash,
            castAndCrew = view.cast.orEmpty().map { it.toMovieCast() },
            status = "Available",
            originalLanguage = Dash,
            budget = Dash,
            revenue = Dash,
            similarMovies = emptyList(),
            reviewsAndRatings = ratings(s.voteAverage, s.voteCount),
            etag = first?.etag.orEmpty(),
            seasons = seasons,
        )
    }

    private suspend fun episodeDetails(id: Int): MovieDetails {
        val view = api.tvEpisode(id)
        val e = view.episode
        val s = view.series
        return MovieDetails(
            id = "e-${e.id}",
            videoUri = absolute(view.location),
            subtitleUri = null,
            posterUri = imageUrl("w1280", e.stillPath.ifEmpty { s.backdropPath }),
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
        )
    }

    private fun unavailableDetails(movieId: String) = MovieDetails(
        id = movieId,
        videoUri = "",
        subtitleUri = null,
        posterUri = "",
        name = "Unable to load",
        description = "Check your connection and server, then try again.",
        pgRating = Dash,
        releaseDate = Dash,
        categories = emptyList(),
        duration = Dash,
        director = Dash,
        screenplay = Dash,
        music = Dash,
        castAndCrew = emptyList(),
        status = Dash,
        originalLanguage = Dash,
        budget = Dash,
        revenue = Dash,
        similarMovies = emptyList(),
        reviewsAndRatings = emptyList(),
    )

    // ---- Recommendations ----

    /**
     * The server's current recommendation sets (they depend on the date), each A–Z.
     * Empty when there are none or the server can't be reached.
     */
    fun getRecommendations(): Flow<List<Recommendation>> = listFlow {
        home().recommendMovies.orEmpty()
            .filter { it.name.isNotBlank() && it.movies.isNotEmpty() }
            .map { r -> Recommendation(r.name, r.movies.sortedAZ().map { it.toMovie(endpoint) }) }
    }

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
            photoUri = p.profilePath?.let { imageUrl("w342", it) }.orEmpty(),
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
            .sortedBy { it.sortName.ifEmpty { it.name }.lowercase(Locale.ROOT) }
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
        (movies + shows).sortedBy { it.first.lowercase(Locale.ROOT) }.map { it.second }
    }

    // ---- Helpers ----

    private fun absolute(location: String): String = when {
        location.isEmpty() -> ""
        location.startsWith("http://") || location.startsWith("https://") -> location
        else -> endpoint + location
    }

    /** By the server's sort title (e.g. without a leading "The"), falling back to the title. */
    private fun List<ApiMovie>.sortedAZ(): List<ApiMovie> =
        sortedBy { it.sortTitle.ifEmpty { it.title }.lowercase(Locale.ROOT) }

    private fun imageUrl(size: String, path: String): String =
        if (path.isEmpty()) "" else "$endpoint/img/tm/$size$path"

    private fun ApiMovie.toMovie(endpoint: String, long: Boolean = false): Movie {
        val poster = if (long) {
            imageUrl("w1280", backdropPath.ifEmpty { posterPath })
        } else {
            imageUrl("w342", posterPath.ifEmpty { backdropPath })
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
            imageUrl("w1280", backdropPath.ifEmpty { posterPath })
        } else {
            imageUrl("w342", posterPath.ifEmpty { backdropPath })
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
        stillUri = imageUrl("w300", stillPath),
        duration = durationOf(runtime),
    )

    private fun ApiCast.toMovieCast() = MovieCast(
        id = peid.toString(),
        characterName = character,
        realName = person.name,
        avatarUrl = person.profilePath?.let { imageUrl("w185", it) }.orEmpty(),
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

    private fun slug(name: String) = name.lowercase(Locale.US).replace(' ', '-')

    private companion object {
        const val Dash = "—"
    }
}
