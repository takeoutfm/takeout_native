/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.takeoutfm.tv.data.entities

data class MovieDetails(
    val id: String,
    val videoUri: String,
    val subtitleUri: String?,
    val posterUri: String,
    val name: String,
    val description: String,
    val pgRating: String,
    val releaseDate: String,
    val categories: List<String>,
    val duration: String,
    val director: String,
    val screenplay: String,
    val music: String,
    val castAndCrew: List<MovieCast>,
    val status: String,
    val originalLanguage: String,
    val budget: String,
    val revenue: String,
    val similarMovies: MovieList,
    val reviewsAndRatings: List<MovieReviewsAndRatings>,
    /** ETag of the file [videoUri] plays; keys watch progress. */
    val etag: String = "",
    /** For a TV series: its episodes, by season. */
    val seasons: List<Season> = emptyList(),
    val isFavorite: Boolean = false,
    /** Where to add/remove the favorite ("movies/<imid>", "shows/<tvid>"); null if it can't be. */
    val favoritePath: String? = null,
    /** For a series: which episode Play starts, e.g. "S1E3". */
    val playTarget: String? = null,
    /** Short marketing line, e.g. "The Night HE Came Home!"; "" if none. */
    val tagline: String = "",
    /** For an episode: the ones before and after it in play order ("e-<id>"), if any. */
    val previousEpisodeId: String? = null,
    val nextEpisodeId: String? = null,
    /** A movie's YouTube trailers, newest first. */
    val trailers: List<com.takeoutfm.shared.video.Trailer> = emptyList(),
    /** For an episode: its series ("s-<id>"); [seasons] then holds just its season. */
    val seriesId: String? = null,
)
