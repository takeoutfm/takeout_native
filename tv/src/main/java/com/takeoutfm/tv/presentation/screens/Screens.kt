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

package com.takeoutfm.tv.presentation.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.takeoutfm.tv.presentation.screens.categories.CategoryMovieListScreen
import com.takeoutfm.tv.presentation.screens.movies.MovieDetailsScreen
import com.takeoutfm.tv.presentation.screens.music.album.AlbumScreen
import com.takeoutfm.tv.presentation.screens.music.artist.ArtistScreen
import com.takeoutfm.tv.presentation.screens.music.player.MusicPlayerScreen
import com.takeoutfm.tv.presentation.screens.music.podcasts.PodcastScreen
import com.takeoutfm.tv.presentation.screens.person.PersonScreen
import com.takeoutfm.tv.presentation.screens.videoPlayer.VideoPlayerScreen

enum class Screens(
    private val args: List<String>? = null,
    /** A video-mode tab. */
    val isTabItem: Boolean = false,
    val tabIcon: ImageVector? = null,
    /** A music-mode tab. */
    val isMusicTab: Boolean = false,
    /** The tab's label when it isn't the route name. */
    val tabLabel: String? = null,
) {
    Profile,
    Home(isTabItem = true),
    Genres(isTabItem = true),
    Movies(isTabItem = true),
    Shows(isTabItem = true),
    Favorites(isTabItem = true),
    Search(isTabItem = true, tabIcon = Icons.Default.Search),
    CategoryMovieList(listOf(CategoryMovieListScreen.CategoryIdBundleKey)),
    MovieDetails(listOf(MovieDetailsScreen.MovieIdBundleKey)),
    Dashboard,
    VideoPlayer(listOf(VideoPlayerScreen.MovieIdBundleKey)),
    Person(listOf(PersonScreen.PersonIdBundleKey)),
    MusicHome(isMusicTab = true, tabLabel = "Home"),
    Artists(isMusicTab = true),
    MusicFavorites(isMusicTab = true, tabLabel = "Favorites"),
    Playlists(isMusicTab = true),
    Radio(isMusicTab = true),
    Podcasts(isMusicTab = true),
    Artist(listOf(ArtistScreen.ArtistIdBundleKey)),
    Album(listOf(AlbumScreen.AlbumIdBundleKey)),
    Podcast(listOf(PodcastScreen.SeriesIdBundleKey)),
    MusicPlayer(listOf(MusicPlayerScreen.QueueBundleKey)),
    NowPlaying;

    operator fun invoke(): String {
        val argList = StringBuilder()
        args?.let { nnArgs ->
            nnArgs.forEach { arg -> argList.append("/{$arg}") }
        }
        return name + argList
    }

    fun withArgs(vararg args: Any): String {
        val destination = StringBuilder()
        args.forEach { arg -> destination.append("/$arg") }
        return name + destination
    }
}
