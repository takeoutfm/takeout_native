package com.takeoutfm.tv.data.repositories

import com.takeoutfm.shared.video.CastCredit
import com.takeoutfm.shared.video.EpisodeInfo
import com.takeoutfm.shared.video.TakeoutVideoRepository
import com.takeoutfm.shared.video.VideoCard
import com.takeoutfm.shared.video.VideoDetails
import com.takeoutfm.shared.video.VideoKind
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [MovieRepository] backed by a Takeout server: the shared [TakeoutVideoRepository] mapped onto
 * JetStream's entities, with the TV app's display formatting (durations, dollars, "—").
 *
 * Ids are prefixed by kind so one id space covers everything JetStream's screens navigate with:
 * movies are "m-<id>", TV series are "s-<id>", TV episodes are "e-<id>".
 *
 * List items carry an empty videoUri; the stream location is only resolved in [getMovieDetails],
 * which is what the details and player screens call.
 */
@Singleton
class TakeoutMovieRepository @Inject constructor(
    private val video: TakeoutVideoRepository,
) : MovieRepository {

    /** A list loaded by [block]; a failed load emits an empty list. */
    private fun cards(long: Boolean = false, block: suspend () -> List<VideoCard>): Flow<MovieList> =
        flow { emit(orEmpty { block() }.map { it.toMovie(long) }) }

    // ---- Home ----

    override fun getFeaturedMovies(): Flow<MovieList> = cards(long = true) { video.featured() }

    /** Shown as "Recently Added". */
    override fun getTrendingMovies(): Flow<MovieList> = cards { video.recentlyAdded() }

    /** Shown as "Recommended". */
    override fun getTop10Movies(): Flow<MovieList> = cards { video.recommended(0, limit = 10) }

    /** Shown as "New Releases". */
    override fun getNowPlayingMovies(): Flow<MovieList> = cards { video.newReleases() }

    // ---- Categories (genres) ----

    override fun getMovieCategories(): Flow<MovieCategoryList> = flow {
        emit(orEmpty { video.genres() }.map { MovieCategory(id = it.id, name = it.name) })
    }

    override suspend fun getMovieCategoryDetails(categoryId: String): MovieCategoryDetails {
        val genre = video.genre(categoryId)
        return MovieCategoryDetails(
            id = genre.genre.id,
            name = genre.genre.name,
            movies = genre.movies.map { it.toMovie() },
        )
    }

    // ---- Details ----

    /** Throws if the server can't be reached or the title is gone; screens show an error. */
    override suspend fun getMovieDetails(movieId: String): MovieDetails =
        video.details(movieId).toMovieDetails()

    // ---- Continue Watching ----

    /**
     * Movies and TV episodes started within the last [days] days and not finished, most recently
     * watched first. Cards are landscape: movie backdrops, episode stills.
     */
    fun getContinueWatching(days: Int): Flow<MovieList> =
        cards(long = true) { video.continueWatching(days) }

    // ---- Recommendations ----

    /**
     * The current recommendation sets, following the library index (empty when the server has
     * none). [long] gives landscape backdrops instead of posters, for wide cards.
     */
    fun getRecommendations(long: Boolean = false): Flow<List<Recommendation>> =
        video.recommendations().map { sets ->
            sets.map { r -> Recommendation(r.name, r.titles.map { it.toMovie(long) }) }
        }

    // ---- Favorites ----

    suspend fun setFavorite(favoritePath: String, favorite: Boolean) =
        video.setFavorite(favoritePath, favorite)

    // ---- People ----

    /** [personId] is the TMDB person id, as carried by [MovieCast.id]. */
    suspend fun getPersonDetails(personId: String): PersonDetails {
        val p = video.person(personId)
        return PersonDetails(
            id = p.id,
            name = p.name,
            photoUri = p.photoUri,
            lifeSummary = p.lifeSummary,
            bio = p.bio,
            credits = p.credits.map { g -> Credits(g.kind, g.titles.map { it.toMovie() }) },
        )
    }

    // ---- Search ----

    suspend fun search(query: String): SearchResults {
        val r = video.search(query)
        return SearchResults(
            movies = r.movies.map { it.toMovie() },
            shows = r.shows.map { it.toMovie() },
            episodes = r.episodes.map { it.toMovie() },
        )
    }

    override suspend fun searchMovies(query: String): MovieList =
        video.searchMovies(query).map { it.toMovie() }

    // ---- Movies / TV tabs ----

    override fun getMoviesWithLongThumbnail(): Flow<MovieList> =
        cards(long = true) { video.moviesUnsorted() }

    /** The whole movie library, A–Z. */
    override fun getMovies(): Flow<MovieList> = cards { video.movies() }

    override fun getPopularFilmsThisWeek(): Flow<MovieList> = cards { video.recommended(1) }

    /** Every TV series, A–Z. */
    override fun getTVShows(): Flow<MovieList> = cards { video.shows() }

    override fun getBingeWatchDramas(): Flow<MovieList> = cards(long = true) { video.recentShows() }

    /** Favorite movies and TV shows together, A–Z. */
    override fun getFavouriteMovies(): Flow<MovieList> = cards { video.favorites() }

    // ---- Mapping ----

    /** [long] gives landscape backdrops instead of posters, for wide cards. */
    private fun VideoCard.toMovie(long: Boolean = false) = Movie(
        id = id,
        videoUri = "",
        subtitleUri = null,
        posterUri = if (long) backdropUri else posterUri,
        name = title,
        description = description,
        progress = progress,
    )

    private fun VideoDetails.toMovieDetails() = MovieDetails(
        id = id,
        videoUri = videoUri,
        subtitleUri = null,
        posterUri = backdropUri,
        name = title,
        description = description,
        pgRating = rating.ifEmpty { "NR" },
        releaseDate = year,
        categories = genres,
        duration = if (kind == VideoKind.Series) {
            "$seasonCount seasons · $episodeCount episodes"
        } else {
            durationOf(runtimeMinutes)
        },
        director = names(directors),
        screenplay = names(writers),
        music = names(composers),
        castAndCrew = cast.map { it.toMovieCast() },
        status = "Available",
        originalLanguage = Dash,
        budget = formatDollars(budget),
        revenue = formatDollars(revenue),
        similarMovies = similar.map { it.toMovie() },
        reviewsAndRatings = ratings(voteAverage, voteCount),
        etag = etag,
        isFavorite = isFavorite,
        favoritePath = favoritePath,
        tagline = tagline,
        seasons = seasons.map { s -> Season(s.number, s.episodes.map { it.toEpisode() }) },
        playTarget = playTarget,
        previousEpisodeId = previousEpisodeId,
        nextEpisodeId = nextEpisodeId,
    )

    private fun EpisodeInfo.toEpisode() = Episode(
        id = id,
        season = season,
        episode = episode,
        name = name,
        description = description,
        stillUri = stillUri,
        duration = durationOf(runtimeMinutes),
    )

    private fun CastCredit.toMovieCast() = MovieCast(
        id = personId,
        characterName = role,
        realName = name,
        avatarUrl = avatarUri,
    )

    private fun names(list: List<String>): String = list.joinToString(", ").ifEmpty { Dash }

    private fun durationOf(minutes: Int): String =
        if (minutes <= 0) Dash else "${minutes / 60}h ${minutes % 60}m"

    private fun ratings(average: Float?, count: Int): List<MovieReviewsAndRatings> =
        if (average == null) {
            emptyList()
        } else {
            listOf(
                MovieReviewsAndRatings(
                    reviewerName = "TMDB",
                    reviewerIconUri = "",
                    reviewCount = count.toString(),
                    reviewRating = String.format(Locale.US, "%.1f / 10", average),
                )
            )
        }

    private suspend fun <T> orEmpty(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val Dash = "—"

        /** 325000 -> "$325,000"; 0 (unknown) -> "—". */
        fun formatDollars(amount: Long): String =
            if (amount <= 0) Dash else "$" + String.format(Locale.US, "%,d", amount)
    }
}
