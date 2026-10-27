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

package com.takeoutfm.tv.presentation.screens.movies

import com.takeoutfm.tv.presentation.common.ItemDirection
import com.takeoutfm.tv.presentation.common.TileRow
import com.takeoutfm.shared.video.Trailer
import coil.compose.AsyncImage
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import com.takeoutfm.tv.R
import com.takeoutfm.tv.data.entities.Movie
import com.takeoutfm.tv.data.entities.MovieDetails
import com.takeoutfm.tv.data.util.StringConstants
import com.takeoutfm.tv.presentation.common.DetailsSkeleton
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.MoviesRow
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.utils.FocusMemory
import com.takeoutfm.tv.presentation.utils.RestoreRememberedFocus
import com.takeoutfm.tv.presentation.utils.rememberFocusMemory

object MovieDetailsScreen {
    const val MovieIdBundleKey = "movieId"
}

@Composable
fun MovieDetailsScreen(
    goToMoviePlayer: (startOver: Boolean) -> Unit,
    openPerson: (personId: String) -> Unit,
    openEpisode: (episodeId: String) -> Unit,
    openSeries: (seriesId: String) -> Unit,
    onBackPressed: () -> Unit,
    refreshScreenWithNewMovie: (Movie) -> Unit,
    movieDetailsScreenViewModel: MovieDetailsScreenViewModel = hiltViewModel()
) {
    val uiState by movieDetailsScreenViewModel.uiState.collectAsStateWithLifecycle()

    when (val s = uiState) {
        is MovieDetailsScreenUiState.Loading -> {
            DetailsSkeleton(modifier = Modifier.fillMaxSize())
        }

        is MovieDetailsScreenUiState.Error -> {
            Error(modifier = Modifier.fillMaxSize(), onRetry = movieDetailsScreenViewModel::retry)
        }

        is MovieDetailsScreenUiState.Done -> {
            val resumePositionMs by movieDetailsScreenViewModel.resumePositionMs
                .collectAsStateWithLifecycle()
            val isFavorite by movieDetailsScreenViewModel.isFavorite.collectAsStateWithLifecycle()
            // Re-runs when coming back from the player, so the button reflects the new position.
            LaunchedEffect(s.movieDetails.etag) {
                movieDetailsScreenViewModel.refreshResumePosition(s.movieDetails.etag)
            }
            Details(
                movieDetails = s.movieDetails,
                resumePositionMs = resumePositionMs,
                isFavorite = isFavorite,
                onToggleFavorite = movieDetailsScreenViewModel::toggleFavorite,
                focusMemory = rememberFocusMemory(movieDetailsScreenViewModel.focusedCardKey),
                goToMoviePlayer = goToMoviePlayer,
                openPerson = openPerson,
                openEpisode = openEpisode,
                openSeries = openSeries,
                onBackPressed = onBackPressed,
                refreshScreenWithNewMovie = refreshScreenWithNewMovie,
                modifier = Modifier
                    .fillMaxSize()
                    .animateContentSize()
            )
        }
    }
}

@Composable
private fun Details(
    movieDetails: MovieDetails,
    resumePositionMs: Long,
    isFavorite: Boolean,
    onToggleFavorite: (favoritePath: String) -> Unit,
    focusMemory: FocusMemory,
    goToMoviePlayer: (startOver: Boolean) -> Unit,
    openPerson: (personId: String) -> Unit,
    openEpisode: (episodeId: String) -> Unit,
    openSeries: (seriesId: String) -> Unit,
    onBackPressed: () -> Unit,
    refreshScreenWithNewMovie: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    val childPadding = rememberChildPadding()
    val context = LocalContext.current
    // Only when something can play YouTube links (the YouTube app on Google TV).
    val openTrailer: ((Trailer) -> Unit)? = remember(context) {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=x"))
        if (context.packageManager.resolveActivity(probe, 0) == null) {
            null
        } else {
            { trailer: Trailer ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailer.uri))) }
                Unit
            }
        }
    }

    BackHandler(onBack = onBackPressed)
    RestoreRememberedFocus(focusMemory)
    LazyColumn(
        contentPadding = PaddingValues(bottom = 135.dp),
        modifier = modifier,
    ) {
        item {
            MovieDetails(
                movieDetails = movieDetails,
                resumePositionMs = resumePositionMs,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                // Back from the player then lands on Play, not on a card opened earlier.
                goToMoviePlayer = { startOver ->
                    focusMemory.lastKey.value = null
                    goToMoviePlayer(startOver)
                },
                openSeries = openSeries,
                openEpisode = openEpisode,
                openTrailer = openTrailer,
            )
        }

        items(movieDetails.seasons, key = { "season-${it.number}" }) { season ->
            SeasonEpisodesRow(
                season = season,
                focusMemory = focusMemory,
                // Opens the episode's page (on an episode's page, another of its season).
                onEpisodeSelected = { openEpisode(it.id) }
            )
        }

        if (movieDetails.trailers.size > 1 && openTrailer != null) item {
            TileRow(
                items = movieDetails.trailers,
                key = { "trailer-${it.key}" },
                itemTitle = { it.name },
                itemImage = { trailer, _, imageModifier ->
                    AsyncImage(
                        model = trailer.thumbnailUri,
                        contentDescription = trailer.name,
                        contentScale = ContentScale.Crop,
                        modifier = imageModifier.fillMaxWidth(),
                    )
                },
                itemDirection = ItemDirection.Horizontal,
                title = stringResource(R.string.trailers),
                titleStyle = MaterialTheme.typography.titleMedium,
                onItemSelected = openTrailer,
                focusMemory = focusMemory,
            )
        }

        if (movieDetails.castAndCrew.isNotEmpty()) item {
            CastAndCrewList(
                castAndCrew = movieDetails.castAndCrew,
                focusMemory = focusMemory,
                onPersonSelected = { openPerson(it.id) }
            )
        }

        if (movieDetails.similarMovies.isNotEmpty()) item {
            MoviesRow(
                title = StringConstants
                    .Composable
                    .movieDetailsScreenSimilarTo(movieDetails.name),
                titleStyle = MaterialTheme.typography.titleMedium,
                movieList = movieDetails.similarMovies,
                // Back from the similar title returns focus to its card.
                focusMemory = focusMemory,
                onMovieSelected = refreshScreenWithNewMovie
            )
        }

        if (movieDetails.reviewsAndRatings.isNotEmpty()) item {
            MovieReviews(
                modifier = Modifier.padding(top = childPadding.top),
                reviewsAndRatings = movieDetails.reviewsAndRatings
            )
        }

        item {
            Box(
                modifier = Modifier
                    .padding(horizontal = childPadding.start)
                    .padding(BottomDividerPadding)
                    .fillMaxWidth()
                    .height(1.dp)
                    .alpha(0.15f)
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = childPadding.start),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val itemModifier = Modifier.width(192.dp)

                TitleValueText(
                    modifier = itemModifier,
                    title = stringResource(R.string.status),
                    value = movieDetails.status
                )
                TitleValueText(
                    modifier = itemModifier,
                    title = stringResource(R.string.original_language),
                    value = movieDetails.originalLanguage
                )
                TitleValueText(
                    modifier = itemModifier,
                    title = stringResource(R.string.budget),
                    value = movieDetails.budget
                )
                TitleValueText(
                    modifier = itemModifier,
                    title = stringResource(R.string.revenue),
                    value = movieDetails.revenue
                )
            }
        }
    }
}

private val BottomDividerPadding = PaddingValues(vertical = 48.dp)
