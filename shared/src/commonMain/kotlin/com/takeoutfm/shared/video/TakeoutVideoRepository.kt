package com.takeoutfm.shared.video

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutSession
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.library.LibraryIndex
import com.takeoutfm.shared.library.TakeoutLibrary
import com.takeoutfm.shared.model.ApiCast
import com.takeoutfm.shared.model.ApiTrailer
import com.takeoutfm.shared.model.ApiEpisode
import com.takeoutfm.shared.model.ApiMovie
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.model.ApiPerson
import com.takeoutfm.shared.model.ApiSeries
import com.takeoutfm.shared.model.ApiTvListView
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.progress.instant
import com.takeoutfm.shared.util.RequestCache
import com.takeoutfm.shared.util.sortKey
import com.takeoutfm.shared.util.sortedByTitle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The server's movies and TV for every app's video screens, as [VideoCard]s and
 * [VideoDetails]. List cards carry no stream; the location is only resolved in [details].
 * Heavy work (sorting and mapping a whole library) runs on [Dispatchers.Default].
 */
class TakeoutVideoRepository(
    private val api: TakeoutApi,
    private val session: TakeoutSession,
    private val library: TakeoutLibrary,
    private val urls: TakeoutUrls,
    private val progress: ProgressSource,
) {
    private val cache = RequestCache { session.tokens?.mediaToken.orEmpty() }

    /** The library index, fetching it first if there isn't one yet. Null if that fails. */
    private suspend fun index(): LibraryIndex? {
        if (library.index.value == null) library.refresh()
        return library.index.value
    }

    private suspend fun <T> background(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { block() }

    // ---- Home ----

    /** Banner titles: new releases, else recently added. */
    suspend fun featured(): List<VideoCard> = background {
        val h = library.home()
        (h.newMovies ?: h.addedMovies).orEmpty().take(FeaturedCount).map { it.toCard() }
    }

    suspend fun recentlyAdded(): List<VideoCard> = background {
        val h = library.home()
        (h.addedMovies ?: h.newMovies).orEmpty().map { it.toCard() }
    }

    suspend fun newReleases(): List<VideoCard> = background {
        val h = library.home()
        (h.newMovies ?: h.addedMovies).orEmpty().map { it.toCard() }
    }

    /** The [n]th recommendation set's titles (falling back to recently added), up to [limit]. */
    suspend fun recommended(n: Int, limit: Int = Int.MAX_VALUE): List<VideoCard> = background {
        val h = library.home()
        (h.recommendMovies?.getOrNull(n)?.movies ?: h.addedMovies).orEmpty().take(limit)
            .map { it.toCard() }
    }

    // ---- Genres ----

    /** The genres that actually occur in this server's movie library, A–Z. */
    suspend fun genres(): List<Genre> = background {
        index()?.movieGenres.orEmpty().distinct().sortedByTitle { it }.map { Genre(slug(it), it) }
    }

    /** A genre's movies A–Z; [genreId] is a [Genre.id]. An unknown genre has no movies. */
    suspend fun genre(genreId: String): GenreMovies = background {
        val name = orNull { genres().firstOrNull { it.id == genreId }?.name } ?: genreId
        val movies = orNull {
            cache.get("genre:$name", ttlMs = 300_000) { api.genre(name) }
                .movies.orEmpty().sortedAZ().map { it.toCard() }
        }.orEmpty()
        GenreMovies(Genre(slug(name), name), movies)
    }

    // ---- Libraries ----

    /** The whole movie library, A–Z. */
    suspend fun movies(): List<VideoCard> = background {
        cache.get("movies") { api.movies() }.movies.orEmpty().sortedAZ().map { it.toCard() }
    }

    /** The whole movie library in the server's order. */
    suspend fun moviesUnsorted(): List<VideoCard> = background {
        cache.get("movies") { api.movies() }.movies.orEmpty().map { it.toCard() }
    }

    /** Every TV series, A–Z. */
    suspend fun shows(): List<VideoCard> = background {
        cache.get("tv-shows") { api.tvShows() }.series.orEmpty()
            .sortedByTitle { it.sortName.ifEmpty { it.name } }
            .map { it.toCard() }
    }

    /** Recently added series. */
    suspend fun recentShows(): List<VideoCard> = background {
        cache.get("tv-recent") { api.tvList() }.series.orEmpty().map { it.toCard() }
    }

    /**
     * Favorite movies and TV shows together, A–Z. Not cached: favorites can change from the
     * other Takeout apps at any time.
     */
    suspend fun favorites(): List<VideoCard> = background {
        val view = api.favorites()
        val movies = view.movies.orEmpty().map { it.sortTitle.ifEmpty { it.title } to it.toCard() }
        val shows = view.shows.orEmpty().map { it.sortName.ifEmpty { it.name } to it.toCard() }
        (movies + shows).sortedByTitle { it.first }.map { it.second }
    }

    /** [path] is a [VideoDetails.favoritePath]. */
    suspend fun setFavorite(path: String, favorite: Boolean) = api.setFavorite(path, favorite)

    // ---- Details ----

    /** Throws if the server can't be reached or the title is gone; screens show an error. */
    suspend fun details(id: String): VideoDetails = when {
        id.startsWith(SeriesPrefix) -> seriesDetails(id.removePrefix(SeriesPrefix).toInt())
        id.startsWith(EpisodePrefix) -> episodeDetails(id.removePrefix(EpisodePrefix).toInt())
        else -> movieDetails(id.removePrefix(MoviePrefix).toInt())
    }

    private suspend fun movieDetails(id: Int): VideoDetails {
        val view = api.movie(id)
        val m = view.movie
        return VideoDetails(
            id = "$MoviePrefix${m.id}",
            kind = VideoKind.Movie,
            videoUri = urls.absolute(view.location),
            backdropUri = urls.tmdbImage("w1280", m.backdropPath.ifEmpty { m.posterPath }),
            title = m.title,
            description = m.overview.ifEmpty { m.tagline },
            tagline = m.tagline,
            rating = m.rating,
            year = m.date.take(4),
            genres = view.genres.orEmpty(),
            runtimeMinutes = m.runtime,
            directors = view.directing.names(),
            writers = view.writing.names(),
            composers = view.crew.orEmpty()
                .filter { it.job.contains("Composer", true) || it.job.contains("Music", true) }
                .map { it.person.name }.distinct(),
            cast = castAndCrew(view.cast, view.directing),
            budget = dollars(m.budget),
            revenue = dollars(m.revenue),
            similar = view.other.orEmpty().map { it.toCard() },
            voteAverage = m.voteAverage?.takeIf { it > 0f },
            voteCount = m.voteCount ?: 0,
            etag = m.etag,
            isFavorite = view.isFavorite,
            favoritePath = m.imid.ifEmpty { null }?.let { "movies/$it" },
            trailers = trailers(view.trailers.orEmpty()),
        )
    }

    /** Series details list every episode by season; Play starts [nextEpisode]. */
    private suspend fun seriesDetails(id: Int): VideoDetails {
        val view = api.tvSeries(id)
        val s = view.series
        val episodes = view.episodes.orEmpty()
        val first = nextEpisode(episodes)
        val location = first?.let { orNull { api.tvEpisode(it.id).location } }.orEmpty()
        return VideoDetails(
            id = "$SeriesPrefix${s.id}",
            kind = VideoKind.Series,
            videoUri = urls.absolute(location),
            backdropUri = urls.tmdbImage("w1280", s.backdropPath.ifEmpty { s.posterPath }),
            title = s.name,
            description = s.overview,
            tagline = s.tagline,
            rating = s.rating,
            year = s.date.take(4),
            genres = view.genres.orEmpty(),
            seasonCount = s.seasonCount,
            episodeCount = s.episodeCount,
            directors = view.directing.names(),
            writers = view.writing.names(),
            cast = castAndCrew(view.cast, view.directing),
            voteAverage = s.voteAverage?.takeIf { it > 0f },
            voteCount = s.voteCount ?: 0,
            etag = first?.etag.orEmpty(),
            isFavorite = view.isFavorite,
            favoritePath = s.tvid.takeIf { it > 0 }?.let { "shows/$it" },
            seasons = seasons(episodes),
            playTarget = first?.let { "S${it.season}E${it.episode}" },
        )
    }

    private fun seasons(episodes: List<ApiEpisode>): List<SeasonInfo> =
        episodes
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .groupBy { it.season }
            .entries
            // Specials (season 0) go last.
            .sortedWith(compareBy({ it.key == 0 }, { it.key }))
            .map { (number, list) -> SeasonInfo(number, list.map { it.toEpisodeInfo() }) }

    /**
     * The episode Play should start: the most recently watched episode if it's unfinished, else
     * the one after it; with no progress (or after the last one), the first. Specials (season 0)
     * only play when there are no regular episodes.
     */
    private suspend fun nextEpisode(episodes: List<ApiEpisode>): ApiEpisode? {
        val ordered = playOrder(episodes)
        if (ordered.isEmpty()) return null
        val offsets = orNull { progress.latest() }.orEmpty()
        return nextInOrder(ordered, offsets)
    }

    private suspend fun episodeDetails(id: Int): VideoDetails {
        val view = api.tvEpisode(id)
        val e = view.episode
        val s = view.series
        // Neighbours for the player's previous/next episode buttons, and the season's episodes;
        // none if the series won't load.
        val all = orNull { api.tvSeries(s.id).episodes.orEmpty() }.orEmpty()
        val order = playOrder(all)
        val index = order.indexOfFirst { it.id == e.id }
        return VideoDetails(
            id = "$EpisodePrefix${e.id}",
            kind = VideoKind.Episode,
            videoUri = urls.absolute(view.location),
            backdropUri = urls.tmdbImage("w1280", e.stillPath.ifEmpty { s.backdropPath }),
            title = "${s.name} · S${e.season}E${e.episode} ${e.name}".trim(),
            description = e.overview,
            // Episodes have none of their own; use the series'.
            tagline = s.tagline,
            rating = s.rating,
            year = e.date.take(4),
            genres = emptyList(),
            runtimeMinutes = e.runtime,
            directors = view.directing.names(),
            writers = view.writing.names(),
            cast = castAndCrew(view.cast, view.directing),
            voteAverage = e.voteAverage?.takeIf { it > 0f },
            voteCount = e.voteCount ?: 0,
            etag = e.etag,
            episode = EpisodeContext(
                seriesId = "$SeriesPrefix${s.id}",
                seriesTitle = s.name,
                season = e.season,
                episode = e.episode,
                name = e.name,
                airDate = e.date.take(10).takeIf { it.length == 10 && !it.startsWith("0001") }.orEmpty(),
                seasonEpisodes = all.filter { it.season == e.season }.sortedBy { it.episode }.map { it.toEpisodeInfo() },
            ),
            previousEpisodeId = order.getOrNull(index - 1)?.takeIf { index > 0 }
                ?.let { "$EpisodePrefix${it.id}" },
            nextEpisodeId = order.getOrNull(index + 1)?.takeIf { index >= 0 }
                ?.let { "$EpisodePrefix${it.id}" },
        )
    }

    // ---- Continue Watching ----
    // Progress is keyed by media file ETag only, so titles are found by matching ETags against
    // the (cached) movie and TV libraries.

    /**
     * Movies and TV episodes started within the last [days] days and not finished, most recently
     * watched first, each with its [VideoCard.progress].
     */
    suspend fun continueWatching(days: Int): List<VideoCard> = background {
        val offsets = progress.inProgress(days)
        if (offsets.isEmpty()) return@background emptyList()
        // Only fetch the libraries the server says it has.
        val index = index()
        val movies = if (index?.hasMovies != false) {
            cache.get("movies") { api.movies() }.movies.orEmpty()
                .filter { it.etag.isNotEmpty() }.associateBy { it.etag }
        } else {
            emptyMap()
        }
        val tv = if (index?.hasShows != false) recentTv() else ApiTvListView()
        val episodes = tv.episodes.orEmpty().filter { it.etag.isNotEmpty() }.associateBy { it.etag }
        val seriesByTvid = tv.series.orEmpty().associateBy { it.tvid }
        offsets.mapNotNull { o ->
            val m = movies[o.etag]
            if (m != null) return@mapNotNull m.toCard().copy(progress = fraction(o, m.runtime))
            val e = episodes[o.etag] ?: return@mapNotNull null
            e.toCard(seriesByTvid[e.tvid]).copy(progress = fraction(o, e.runtime))
        }
    }

    private suspend fun recentTv() = cache.get("tv-recent") { api.tvList() }

    // ---- Recommendations ----

    /**
     * The current recommendation sets, each A–Z, following the library index (empty when the
     * server has none). Starts from the saved index, so tabs can appear before anything is
     * fetched.
     */
    fun recommendations(): Flow<List<RecommendationSet>> =
        library.index
            .map { index ->
                index?.recommendations.orEmpty()
                    .takeIf { index?.hasRecommendations == true }.orEmpty()
                    .filter { it.name.isNotBlank() && it.movies.isNotEmpty() }
                    .map { r -> RecommendationSet(r.name, r.movies.sortedAZ().map { it.toCard() }) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // ---- People ----

    /** [personId] is the TMDB person id, as carried by [CastCredit.personId]. */
    suspend fun person(personId: String): PersonInfo = background {
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
            CreditKind.MoviesActing to view.movies.starring.moviesNewestFirst(),
            CreditKind.MoviesDirecting to view.movies.directing.moviesNewestFirst(),
            CreditKind.MoviesWriting to view.movies.writing.moviesNewestFirst(),
            CreditKind.ShowsActing to view.shows.starring.showsNewestFirst(),
            CreditKind.ShowsDirecting to view.shows.directing.showsNewestFirst(),
            CreditKind.ShowsWriting to view.shows.writing.showsNewestFirst(),
        ).filter { it.second.isNotEmpty() }.map { (kind, titles) -> CreditGroup(kind, titles) }
        PersonInfo(
            id = p.peid.toString(),
            name = p.name,
            photoUri = p.profilePath?.let { urls.tmdbImage("w342", it) }.orEmpty(),
            lifeSummary = listOfNotNull(years, p.birthplace.ifEmpty { null }).joinToString(" · "),
            bio = p.bio,
            credits = credits,
        )
    }

    /** Newest first, like a filmography. */
    private fun List<ApiMovie>?.moviesNewestFirst(): List<VideoCard> =
        orEmpty().distinctBy { it.id }.sortedByDescending { it.date }.map { it.toCard() }

    private fun List<ApiSeries>?.showsNewestFirst(): List<VideoCard> =
        orEmpty().distinctBy { it.id }.sortedByDescending { it.date }.map { it.toCard() }

    // ---- Search ----

    /**
     * Movies and TV episodes come from /api/search, requiring every word. The server doesn't
     * search TV series, so those are matched here against the (cached) series list by name.
     * Empty when the server can't be reached.
     */
    suspend fun search(query: String): VideoSearchResults = background {
        val q = sortKey(query)
        if (q.isEmpty()) return@background VideoSearchResults()
        orNull {
            val view = api.search(allWordsQuery(query))
            val tv = if (index()?.hasShows != false) recentTv() else ApiTvListView()
            val seriesByTvid = tv.series.orEmpty().associateBy { it.tvid }
            VideoSearchResults(
                // Titles containing the whole phrase first, then the rest, each A–Z.
                movies = view.movies.orEmpty().sortedAZ()
                    .sortedBy { q !in sortKey(it.title) } // stable: A–Z within each group
                    .map { it.toCard() },
                shows = tv.series.orEmpty()
                    .filter { q in sortKey(it.name) }
                    .sortedByTitle { it.sortName.ifEmpty { it.name } }
                    .map { it.toCard() },
                episodes = view.tvEpisodes.orEmpty().map { it.toCard(seriesByTvid[it.tvid]) },
            )
        } ?: VideoSearchResults()
    }

    /** Movies matching [query] as the server parses it; empty on errors. */
    suspend fun searchMovies(query: String): List<VideoCard> =
        orNull { api.search(query).movies.orEmpty().map { it.toCard() } }.orEmpty()

    // ---- Mapping ----

    /** By the server's sort title (e.g. without a leading "The"), falling back to the title. */
    private fun List<ApiMovie>.sortedAZ(): List<ApiMovie> =
        sortedByTitle { it.sortTitle.ifEmpty { it.title } }

    private fun ApiMovie.toCard() = VideoCard(
        id = "$MoviePrefix$id",
        title = title,
        description = overview.ifEmpty { tagline },
        posterUri = urls.tmdbImage("w342", posterPath.ifEmpty { backdropPath }),
        backdropUri = urls.tmdbImage("w1280", backdropPath.ifEmpty { posterPath }),
    )

    private fun ApiSeries.toCard() = VideoCard(
        id = "$SeriesPrefix$id",
        title = name,
        description = overview,
        posterUri = urls.tmdbImage("w342", posterPath.ifEmpty { backdropPath }),
        backdropUri = urls.tmdbImage("w1280", backdropPath.ifEmpty { posterPath }),
    )

    /** An episode as a landscape card: its still, titled "Series · S1E2 Name". */
    private fun ApiEpisode.toCard(series: ApiSeries?): VideoCard {
        val still = urls.tmdbImage("w300", stillPath)
            .ifEmpty { urls.tmdbImage("w780", series?.backdropPath.orEmpty()) }
        return VideoCard(
            id = "$EpisodePrefix$id",
            title = listOfNotNull(series?.name, "S${season}E$episode $name".trim()).joinToString(" · "),
            description = overview,
            posterUri = still,
            backdropUri = still,
        )
    }

    private fun ApiEpisode.toEpisodeInfo() = EpisodeInfo(
        id = "$EpisodePrefix$id",
        season = season,
        episode = episode,
        name = name,
        description = overview,
        stillUri = urls.tmdbImage("w300", stillPath),
        runtimeMinutes = runtime,
    )

    private fun ApiPerson.avatar() = profilePath?.let { urls.tmdbImage("w185", it) }.orEmpty()

    private fun castAndCrew(cast: List<ApiCast>?, directing: List<ApiPerson>?) =
        withDirectorsFirst(
            directors = directing.orEmpty().filter { it.peid > 0 }.map {
                CastCredit(it.peid.toString(), it.name, "", it.avatar())
            },
            cast = cast.orEmpty().map {
                CastCredit(it.peid.toString(), it.person.name, it.character, it.person.avatar())
            },
        )

    private fun List<ApiPerson>?.names(): List<String> = orEmpty().map { it.name }.distinct()

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * YouTube trailers, the main one first: "Official Trailer"s, then other trailers, then
         * the rest (spots, sign-language and audio-described versions), newest first in each.
         * Others (not on YouTube) are dropped.
         */
        fun trailers(trailers: List<ApiTrailer>): List<Trailer> =
            trailers
                .filter { it.key.isNotEmpty() && it.site.equals("YouTube", ignoreCase = true) }
                .distinctBy { it.key }
                .sortedWith(compareBy<ApiTrailer>({ trailerRank(it.name) }).thenByDescending { it.date })
                .map {
                    Trailer(
                        name = it.name,
                        key = it.key,
                        uri = it.url.ifEmpty { "https://www.youtube.com/watch?v=${it.key}" },
                        thumbnailUri = "https://i.ytimg.com/vi/${it.key}/hqdefault.jpg",
                    )
                }

        private val SecondaryTrailer = Regex("sign language|audio descri|tickets|teaser|spot|featurette", RegexOption.IGNORE_CASE)

        private fun trailerRank(name: String): Int = when {
            SecondaryTrailer.containsMatchIn(name) -> 2
            name.contains("official trailer", ignoreCase = true) -> 0
            name.contains("trailer", ignoreCase = true) -> 1
            else -> 2
        }

        const val MoviePrefix = "m-"
        const val SeriesPrefix = "s-"
        const val EpisodePrefix = "e-"
        private const val FeaturedCount = 8

        /** Seasons in order, then episodes; specials (season 0) only when there's nothing else. */
        fun playOrder(episodes: List<ApiEpisode>): List<ApiEpisode> =
            episodes.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
                .ifEmpty { episodes.sortedBy { it.episode } }

        /**
         * In [ordered] episodes: the most recently watched one if it's unfinished, else the one
         * after it; with no progress (or after the last one), the first.
         */
        fun nextInOrder(ordered: List<ApiEpisode>, offsets: Map<String, ApiOffset>): ApiEpisode? {
            if (ordered.isEmpty()) return null
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

        /** Offset over duration, falling back to the runtime (minutes) when the duration is unknown. */
        fun fraction(o: ApiOffset, runtimeMinutes: Int): Float? {
            val total = if (o.duration > 0) o.duration else runtimeMinutes * 60
            return if (total > 0) (o.offset.toFloat() / total).coerceIn(0f, 1f) else null
        }

        /** "325000" -> 325000; empty, zero or unparseable (TMDB's "unknown") -> 0. */
        fun dollars(amount: String): Long = amount.trim().toLongOrNull()?.takeIf { it > 0 } ?: 0

        /** Genre ids: lowercase with dashes. */
        fun slug(name: String) = name.lowercase().replace(' ', '-')

        /** Go's zero time ("0001-01-01T...") means unknown. */
        private fun String.year(): Int? = take(4).toIntOrNull()?.takeIf { it > 1000 }
    }
}
