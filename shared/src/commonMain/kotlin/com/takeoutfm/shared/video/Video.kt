package com.takeoutfm.shared.video

import com.takeoutfm.shared.model.ApiOffset

// Video entities for every app's screens. Ids are prefixed by kind so one id space covers
// everything: movies "m-<id>", TV series "s-<id>", episodes "e-<id>". Image URIs are full URLs,
// or "" when the server has none.

/** A movie, series or episode in a list. */
data class VideoCard(
    val id: String,
    val title: String,
    val description: String,
    /** Portrait poster (episodes: the still). */
    val posterUri: String,
    /** Landscape backdrop, for wide cards (episodes: the still). */
    val backdropUri: String,
    /** Watched fraction 0–1 for Continue Watching; null elsewhere. */
    val progress: Float? = null,
)

data class Genre(val id: String, val name: String)

data class GenreMovies(val genre: Genre, val movies: List<VideoCard>)

/** A person in Cast & Crew; [role] is the character, or "Director" (see [withDirectorsFirst]). */
data class CastCredit(
    val personId: String,
    val name: String,
    val role: String,
    val avatarUri: String,
)

data class EpisodeInfo(
    val id: String,
    val season: Int,
    val episode: Int,
    val name: String,
    val description: String,
    val stillUri: String,
    val runtimeMinutes: Int,
)

data class SeasonInfo(val number: Int, val episodes: List<EpisodeInfo>)

/** Where an episode sits: its series (for "go to series"), number, air date and season. */
data class EpisodeContext(
    val seriesId: String,
    val seriesTitle: String,
    val season: Int,
    val episode: Int,
    val name: String,
    /** ISO date it aired, "" when unknown. */
    val airDate: String,
    /** Every episode of its season, in order (itself included). */
    val seasonEpisodes: List<EpisodeInfo>,
) {
    /** "S2E5". */
    val label: String get() = "S${season}E$episode"
}

enum class VideoKind { Movie, Series, Episode }

/** Details for a movie, a series (Play starts [playTarget]) or an episode. */
data class VideoDetails(
    val id: String,
    val kind: VideoKind,
    /** The stream to play; "" when there's nothing playable. */
    val videoUri: String,
    val backdropUri: String,
    val title: String,
    val description: String,
    val tagline: String,
    /** Content rating, e.g. "PG-13"; "" when unknown. */
    val rating: String,
    val year: String,
    val genres: List<String>,
    val runtimeMinutes: Int = 0,
    val seasonCount: Int = 0,
    val episodeCount: Int = 0,
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val cast: List<CastCredit> = emptyList(),
    /** Dollars; 0 when unknown. */
    val budget: Long = 0,
    val revenue: Long = 0,
    val similar: List<VideoCard> = emptyList(),
    /** TMDB vote average (0–10) and count; null when unrated. */
    val voteAverage: Float? = null,
    val voteCount: Int = 0,
    /** Media ETag, the key for progress. */
    val etag: String = "",
    val isFavorite: Boolean = false,
    /** For [TakeoutVideoRepository.setFavorite]; null when it can't be a favorite. */
    val favoritePath: String? = null,
    val seasons: List<SeasonInfo> = emptyList(),
    /** Series: the episode Play starts, e.g. "S1E3". */
    val playTarget: String? = null,
    val previousEpisodeId: String? = null,
    val nextEpisodeId: String? = null,
    /** An episode's series, number and season; null for movies and series. */
    val episode: EpisodeContext? = null,
)

enum class CreditKind {
    MoviesActing, MoviesDirecting, MoviesWriting, ShowsActing, ShowsDirecting, ShowsWriting
}

data class CreditGroup(val kind: CreditKind, val titles: List<VideoCard>)

data class PersonInfo(
    val id: String,
    val name: String,
    val photoUri: String,
    /** Year of birth (and death) and birthplace, e.g. "1956–2023 · Chicago, Illinois, USA". */
    val lifeSummary: String,
    val bio: String,
    /** Non-empty credit lists only, in [CreditKind] order. */
    val credits: List<CreditGroup>,
)

data class RecommendationSet(val name: String, val titles: List<VideoCard>)

data class VideoSearchResults(
    val movies: List<VideoCard> = emptyList(),
    val shows: List<VideoCard> = emptyList(),
    val episodes: List<VideoCard> = emptyList(),
)

/** Where the video repository gets watch progress (the app's offline-aware progress store). */
interface ProgressSource {
    /** The newest offset for every ETag. */
    suspend fun latest(): Map<String, ApiOffset>

    /** Started-but-unfinished offsets updated within [days] days, most recent first. */
    suspend fun inProgress(days: Int): List<ApiOffset>
}

/**
 * Cast & Crew: [directors] first, labelled "Director", then [cast]. A director who also acts
 * gets one card, "Director · as <role>", instead of a second one in the cast.
 */
fun withDirectorsFirst(directors: List<CastCredit>, cast: List<CastCredit>): List<CastCredit> {
    val roles = cast.groupBy { it.personId }
    val first = directors.distinctBy { it.personId }.map { director ->
        val role = roles[director.personId].orEmpty().map { it.role }
            .filter { it.isNotBlank() }.distinct().joinToString(" / ")
        director.copy(role = if (role.isEmpty()) "Director" else "Director · as $role")
    }
    val ids = first.map { it.personId }.toSet()
    return first + cast.filter { it.personId !in ids }
}

/**
 * The server takes a Bleve query string, where "evil dead" means evil OR dead; require every
 * word instead ("+evil +dead"). Query syntax characters are dropped from what the user typed.
 */
fun allWordsQuery(query: String): String =
    query.replace(Regex("""[+\-=&|><!(){}\[\]^"~*?:\\/]"""), " ")
        .split(Regex("\\s+")).filter { it.isNotBlank() }
        .joinToString(" ") { "+$it" }
