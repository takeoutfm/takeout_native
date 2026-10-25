/*
 * Takeout API wire models. Field names follow the Takeout server's JSON (PascalCase).
 * Everything is defaulted so a missing/null field never fails decoding.
 */
package com.takeoutfm.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiTokens(
    @SerialName("AccessToken") val accessToken: String = "",
    @SerialName("MediaToken") val mediaToken: String = "",
    @SerialName("RefreshToken") val refreshToken: String = "",
) {
    fun isValid() = accessToken.isNotEmpty() && mediaToken.isNotEmpty() && refreshToken.isNotEmpty()
}

@Serializable
data class ApiRefreshTokens(
    @SerialName("AccessToken") val accessToken: String = "",
    @SerialName("RefreshToken") val refreshToken: String = "",
)

@Serializable
data class ApiAccessCode(
    @SerialName("Code") val code: String = "",
    @SerialName("AccessToken") val accessToken: String = "",
)

@Serializable
data class ApiPerson(
    @SerialName("ID") val id: Int = 0,
    @SerialName("PEID") val peid: Int = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("ProfilePath") val profilePath: String? = null,
    @SerialName("Bio") val bio: String = "",
    @SerialName("Birthplace") val birthplace: String = "",
    @SerialName("Birthday") val birthday: String = "",
    @SerialName("Deathday") val deathday: String = "",
)

@Serializable
data class ApiMovieCredits(
    @SerialName("Starring") val starring: List<ApiMovie>? = null,
    @SerialName("Directing") val directing: List<ApiMovie>? = null,
    @SerialName("Writing") val writing: List<ApiMovie>? = null,
)

@Serializable
data class ApiTvCredits(
    @SerialName("Starring") val starring: List<ApiSeries>? = null,
    @SerialName("Directing") val directing: List<ApiSeries>? = null,
    @SerialName("Writing") val writing: List<ApiSeries>? = null,
)

@Serializable
data class ApiProfileView(
    @SerialName("Person") val person: ApiPerson = ApiPerson(),
    @SerialName("Movies") val movies: ApiMovieCredits = ApiMovieCredits(),
    @SerialName("Shows") val shows: ApiTvCredits = ApiTvCredits(),
)

@Serializable
data class ApiCast(
    @SerialName("PEID") val peid: Int = 0,
    @SerialName("Character") val character: String = "",
    @SerialName("Person") val person: ApiPerson = ApiPerson(),
)

@Serializable
data class ApiCrew(
    @SerialName("PEID") val peid: Int = 0,
    @SerialName("Department") val department: String = "",
    @SerialName("Job") val job: String = "",
    @SerialName("Person") val person: ApiPerson = ApiPerson(),
)

@Serializable
data class ApiMovie(
    @SerialName("ID") val id: Int = 0,
    @SerialName("Title") val title: String = "",
    /** Title for A–Z ordering, e.g. without a leading "The". */
    @SerialName("SortTitle") val sortTitle: String = "",
    @SerialName("Date") val date: String = "",
    @SerialName("Rating") val rating: String = "",
    @SerialName("Tagline") val tagline: String = "",
    @SerialName("Overview") val overview: String = "",
    @SerialName("Budget") val budget: String = "",
    @SerialName("Revenue") val revenue: String = "",
    @SerialName("Runtime") val runtime: Int = 0,
    @SerialName("VoteAverage") val voteAverage: Float? = null,
    @SerialName("VoteCount") val voteCount: Int? = null,
    @SerialName("BackdropPath") val backdropPath: String = "",
    @SerialName("PosterPath") val posterPath: String = "",
    @SerialName("ETag") val etag: String = "",
    /** IMDB id; favorites are keyed by it. */
    @SerialName("IMID") val imid: String = "",
)

@Serializable
data class ApiSeries(
    @SerialName("ID") val id: Int = 0,
    /** TMDB TV id; favorites are keyed by it. */
    @SerialName("TVID") val tvid: Long = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("SortName") val sortName: String = "",
    @SerialName("Tagline") val tagline: String = "",
    @SerialName("Overview") val overview: String = "",
    @SerialName("Date") val date: String = "",
    @SerialName("SeasonCount") val seasonCount: Int = 0,
    @SerialName("EpisodeCount") val episodeCount: Int = 0,
    @SerialName("VoteAverage") val voteAverage: Float? = null,
    @SerialName("VoteCount") val voteCount: Int? = null,
    @SerialName("PosterPath") val posterPath: String = "",
    @SerialName("BackdropPath") val backdropPath: String = "",
    @SerialName("Rating") val rating: String = "",
)

@Serializable
data class ApiEpisode(
    @SerialName("ID") val id: Int = 0,
    /** The series' TMDB TV id, matching [ApiSeries.tvid]. */
    @SerialName("TVID") val tvid: Long = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("Season") val season: Int = 0,
    @SerialName("Episode") val episode: Int = 0,
    @SerialName("Runtime") val runtime: Int = 0,
    @SerialName("Overview") val overview: String = "",
    @SerialName("Date") val date: String = "",
    @SerialName("StillPath") val stillPath: String = "",
    @SerialName("ETag") val etag: String = "",
)

@Serializable
data class ApiRecommend(
    @SerialName("Name") val name: String = "",
    @SerialName("Movies") val movies: List<ApiMovie> = emptyList(),
)

/** POST /api/activity: plays to record. */
@Serializable
data class ApiActivityEvents(
    @SerialName("TrackEvents") val trackEvents: List<ApiTrackEvent> = emptyList(),
)

@Serializable
data class ApiTrackEvent(
    @SerialName("ETag") val etag: String,
    /** RFC 3339, e.g. "2026-10-24T17:05:00Z". */
    @SerialName("Date") val date: String,
)

@Serializable
data class ApiHomeView(
    @SerialName("AddedMovies") val addedMovies: List<ApiMovie>? = null,
    @SerialName("NewMovies") val newMovies: List<ApiMovie>? = null,
    @SerialName("RecommendMovies") val recommendMovies: List<ApiRecommend>? = null,
    @SerialName("AddedReleases") val addedReleases: List<ApiRelease>? = null,
    @SerialName("NewReleases") val newReleases: List<ApiRelease>? = null,
)

/** The user's favorites: movies and shows for video, artists and tracks for music. */
@Serializable
data class ApiFavoriteView(
    @SerialName("Movies") val movies: List<ApiMovie>? = null,
    @SerialName("Shows") val shows: List<ApiSeries>? = null,
    @SerialName("Artists") val artists: List<ApiArtist>? = null,
    @SerialName("Tracks") val tracks: List<ApiTrack>? = null,
)

/** Server summary; only what the TV app needs. */
@Serializable
data class ApiIndexView(
    // Null when an older server doesn't send the flag; treated as "yes" so nothing is hidden.
    @SerialName("HasMusic") val hasMusic: Boolean? = null,
    @SerialName("HasMovies") val hasMovies: Boolean? = null,
    @SerialName("HasShows") val hasShows: Boolean? = null,
    @SerialName("HasRecommendMovies") val hasRecommendMovies: Boolean? = null,
    @SerialName("HasPlaylists") val hasPlaylists: Boolean? = null,
    @SerialName("MovieGenres") val movieGenres: List<String>? = null,
)

@Serializable
data class ApiMoviesView(
    @SerialName("Movies") val movies: List<ApiMovie>? = null,
)

@Serializable
data class ApiMovieView(
    @SerialName("Movie") val movie: ApiMovie = ApiMovie(),
    @SerialName("Location") val location: String = "",
    @SerialName("Other") val other: List<ApiMovie>? = null,
    @SerialName("Cast") val cast: List<ApiCast>? = null,
    @SerialName("Crew") val crew: List<ApiCrew>? = null,
    @SerialName("Directing") val directing: List<ApiPerson>? = null,
    @SerialName("Writing") val writing: List<ApiPerson>? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)

@Serializable
data class ApiGenreView(
    @SerialName("Name") val name: String = "",
    @SerialName("Movies") val movies: List<ApiMovie>? = null,
)

@Serializable
data class ApiTvListView(
    @SerialName("Series") val series: List<ApiSeries>? = null,
    /** /api/tv only: every episode in the library. */
    @SerialName("Episodes") val episodes: List<ApiEpisode>? = null,
)

@Serializable
data class ApiTvSeriesView(
    @SerialName("Series") val series: ApiSeries = ApiSeries(),
    @SerialName("Episodes") val episodes: List<ApiEpisode>? = null,
    @SerialName("Cast") val cast: List<ApiCast>? = null,
    @SerialName("Directing") val directing: List<ApiPerson>? = null,
    @SerialName("Writing") val writing: List<ApiPerson>? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)

@Serializable
data class ApiTvEpisodeView(
    @SerialName("Series") val series: ApiSeries = ApiSeries(),
    @SerialName("Episode") val episode: ApiEpisode = ApiEpisode(),
    @SerialName("Location") val location: String = "",
)

@Serializable
data class ApiSearchView(
    @SerialName("Movies") val movies: List<ApiMovie>? = null,
    @SerialName("TVEpisodes") val tvEpisodes: List<ApiEpisode>? = null,
    @SerialName("Query") val query: String = "",
)

/** Watch progress for one media file. Offset and Duration are seconds; Date is ISO-8601 UTC. */
@Serializable
data class ApiOffset(
    @SerialName("ETag") val etag: String = "",
    @SerialName("Offset") val offset: Int = 0,
    @SerialName("Duration") val duration: Int = 0,
    @SerialName("Date") val date: String = "",
)

@Serializable
data class ApiOffsets(
    @SerialName("Offsets") val offsets: List<ApiOffset> = emptyList(),
)
