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

package com.takeoutfm.tv.data.util

object StringConstants {
    object Movie {
        const val StatusReleased = "Released"
        const val BudgetDefault = "$10M"
        const val WorldWideGrossDefault = "$20M"

        object Reviewer {
            const val FreshTomatoes = "Fresh Tomatoes"
            const val FreshTomatoesImageUrl = ""
            const val ReviewerName = "Rater"
            const val ImageUrl = ""
            const val DefaultCount = "1.8M"
            const val DefaultRating = "9.2"
        }
    }

    object Assets {
        const val Top250Movies = "movies.json"
        const val MostPopularMovies = "movies.json"
        const val InTheaters = "movies.json"
        const val MostPopularTVShows = "movies.json"
        const val MovieCategories = "movieCategories.json"
        const val MovieCast = "movieCast.json"
    }

    object Exceptions {
        const val UnknownException = "Unknown Exception!"
        const val InvalidCategoryId = "Invalid category ID!"
    }

    object Composable {
        object ContentDescription {
            fun moviePoster(movieName: String) = "Movie poster of $movieName"
            fun image(imageName: String) = "image of $imageName"
            const val MoviesCarousel = "Movies Carousel"
            const val UserAvatar = "User Profile Button"
            const val DashboardSearchButton = "Dashboard Search Button"
            const val BrandLogoImage = "Brand Logo Image"
            const val FilterSelected = "Filter Selected"
            fun reviewerName(name: String) = "$name's logo"
        }

        const val CategoryDetailsFailureSubject = "category details"
        const val MoviesFailureSubject = "movies"
        const val MovieDetailsFailureSubject = "movie details"
        const val HomeScreenTrendingTitle = "Recently Added"
        const val HomeScreenNowPlayingMoviesTitle = "New Releases"
        const val PopularFilmsThisWeekTitle = "More to Watch"
        const val BingeWatchDramasTitle = "Recently Added Shows"
        fun movieDetailsScreenSimilarTo(name: String) = "Similar to $name"
        fun reviewCount(count: String) = "$count reviews"

        const val VideoPlayerControlPlaylistButton = "Playlist Button"
        const val VideoPlayerControlAudioButton = "Audio track"
        const val VideoPlayerControlClosedCaptionsButton = "Subtitles"
        const val VideoPlayerControlSettingsButton = "Playback speed"
        const val VideoPlayerControlPlayPauseButton = "Playlist Button"
        const val VideoPlayerControlForward = "Fast forward 10 seconds"
        const val VideoPlayerControlSkipNextButton = "Next episode"
        const val VideoPlayerControlSkipPreviousButton = "Previous episode"
    }
}
