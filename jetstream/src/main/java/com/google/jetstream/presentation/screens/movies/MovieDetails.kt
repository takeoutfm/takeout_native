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

package com.google.jetstream.presentation.screens.movies

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.jetstream.R
import com.google.jetstream.data.entities.MovieDetails
import com.google.jetstream.data.util.StringConstants
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.theme.JetStreamButtonShape
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MovieDetails(
    movieDetails: MovieDetails,
    resumePositionMs: Long,
    isFavorite: Boolean,
    onToggleFavorite: (favoritePath: String) -> Unit,
    goToMoviePlayer: (startOver: Boolean) -> Unit
) {
    val childPadding = rememberChildPadding()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(432.dp)
            .bringIntoViewRequester(bringIntoViewRequester)
    ) {
        MovieImageWithGradients(
            movieDetails = movieDetails,
            modifier = Modifier.fillMaxSize()
        )

        Column(modifier = Modifier.fillMaxWidth(0.55f)) {
            Spacer(modifier = Modifier.height(108.dp))
            Column(
                modifier = Modifier.padding(start = childPadding.start)
            ) {
                MovieLargeTitle(movieTitle = movieDetails.name)

                Column(
                    modifier = Modifier.alpha(0.75f)
                ) {
                    MovieDescription(description = movieDetails.description)
                    DotSeparatedRow(
                        modifier = Modifier.padding(top = 20.dp),
                        texts = listOf(
                            movieDetails.pgRating,
                            movieDetails.releaseDate,
                            movieDetails.categories.joinToString(", "),
                            movieDetails.duration
                        ).filter { it.isNotBlank() && it != "—" } // e.g. episodes have no genres
                    )
                    DirectorScreenplayMusicRow(
                        director = movieDetails.director,
                        screenplay = movieDetails.screenplay,
                        music = movieDetails.music
                    )
                }
                val bringIntoViewOnFocus = Modifier.onFocusChanged {
                    if (it.isFocused) {
                        coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                    }
                }
                Row(
                    // The text column is 55% wide; let the buttons run past it rather than squash.
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .wrapContentWidth(align = Alignment.Start, unbounded = true),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    WatchTrailerButton(
                        modifier = bringIntoViewOnFocus,
                        resumePositionMs = resumePositionMs,
                        playTarget = movieDetails.playTarget,
                        goToMoviePlayer = { goToMoviePlayer(false) }
                    )
                    if (resumePositionMs > 0) {
                        StartOverButton(
                            modifier = bringIntoViewOnFocus,
                            onClick = { goToMoviePlayer(true) }
                        )
                    }
                    movieDetails.favoritePath?.let { path ->
                        FavoriteButton(
                            isFavorite = isFavorite,
                            modifier = bringIntoViewOnFocus,
                            onClick = { onToggleFavorite(path) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchTrailerButton(
    resumePositionMs: Long,
    playTarget: String?,
    modifier: Modifier = Modifier,
    goToMoviePlayer: () -> Unit
) {
    Button(
        onClick = goToMoviePlayer,
        modifier = modifier,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        shape = ButtonDefaults.shape(shape = JetStreamButtonShape)
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = when {
                playTarget != null && resumePositionMs > 0 -> stringResource(
                    R.string.resume_episode_from, playTarget, formatPosition(resumePositionMs)
                )
                playTarget != null -> stringResource(R.string.play_episode, playTarget)
                resumePositionMs > 0 ->
                    stringResource(R.string.resume_from, formatPosition(resumePositionMs))
                else -> stringResource(R.string.watch_trailer)
            },
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun StartOverButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        shape = OutlinedButtonDefaults.shape(shape = JetStreamButtonShape)
    ) {
        Icon(
            imageVector = Icons.Outlined.Replay,
            contentDescription = null
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.start_over),
            style = MaterialTheme.typography.titleSmall
        )
    }
}

/** "♡ Add to favorites", or "♥ Favorite" once it is one; styled like [StartOverButton]. */
@Composable
private fun FavoriteButton(
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        shape = OutlinedButtonDefaults.shape(shape = JetStreamButtonShape)
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = null
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(if (isFavorite) R.string.favorite else R.string.add_to_favorites),
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun DirectorScreenplayMusicRow(
    director: String,
    screenplay: String,
    music: String
) {
    Row(modifier = Modifier.padding(top = 32.dp)) {
        TitleValueText(
            modifier = Modifier
                .padding(end = 32.dp)
                .weight(1f),
            title = stringResource(R.string.director),
            value = director
        )

        TitleValueText(
            modifier = Modifier
                .padding(end = 32.dp)
                .weight(1f),
            title = stringResource(R.string.screenplay),
            value = screenplay
        )

        TitleValueText(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.music),
            value = music
        )
    }
}

@Composable
private fun MovieDescription(description: String) {
    Text(
        text = description,
        style = MaterialTheme.typography.titleSmall.copy(
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal
        ),
        modifier = Modifier.padding(top = 8.dp),
        maxLines = 2
    )
}

@Composable
private fun MovieLargeTitle(movieTitle: String) {
    // Long titles (e.g. "Series · S1E1 Episode name") step down a size and may wrap once.
    val style = if (movieTitle.length > LongTitleLength) {
        MaterialTheme.typography.displaySmall
    } else {
        MaterialTheme.typography.displayMedium
    }
    Text(
        text = movieTitle,
        style = style.copy(fontWeight = FontWeight.Bold),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

private const val LongTitleLength = 22

@Composable
private fun MovieImageWithGradients(
    movieDetails: MovieDetails,
    modifier: Modifier = Modifier,
    gradientColor: Color = MaterialTheme.colorScheme.surface,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(movieDetails.posterUri)
            .crossfade(true).build(),
        contentDescription = StringConstants
            .Composable
            .ContentDescription
            .moviePoster(movieDetails.name),
        contentScale = ContentScale.Crop,
        modifier = modifier.drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, gradientColor),
                    startY = 600f
                )
            )
            drawRect(
                Brush.horizontalGradient(
                    colors = listOf(gradientColor, Color.Transparent),
                    endX = 1000f,
                    startX = 300f
                )
            )
            drawRect(
                Brush.linearGradient(
                    colors = listOf(gradientColor, Color.Transparent),
                    start = Offset(x = 500f, y = 500f),
                    end = Offset(x = 1000f, y = 0f)
                )
            )
        }
    )
}

/** 33_000 -> "0:33", 3_723_000 -> "1:02:03" */
private fun formatPosition(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
