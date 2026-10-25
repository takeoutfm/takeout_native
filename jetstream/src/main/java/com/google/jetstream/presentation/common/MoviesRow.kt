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

package com.google.jetstream.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.jetstream.data.entities.Movie
import com.google.jetstream.data.entities.MovieList
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.utils.FocusMemory

/**
 * [cardWidth] is fixed so a long focused title is ellipsized rather than widening the card
 * (and shoving the rest of the row aside).
 */
enum class ItemDirection(val aspectRatio: Float, val cardWidth: Dp) {
    Vertical(10.5f / 16f, 172.dp),
    Horizontal(16f / 9f, 268.dp),
    /** Album covers and artist photos. */
    Square(1f, 196.dp);
}

@Composable
fun MoviesRow(
    movieList: MovieList,
    modifier: Modifier = Modifier,
    itemDirection: ItemDirection = ItemDirection.Vertical,
    startPadding: Dp = rememberChildPadding().start,
    endPadding: Dp = rememberChildPadding().end,
    title: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.headlineLarge.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp
    ),
    showItemTitle: Boolean = true,
    showIndexOverImage: Boolean = false,
    focusMemory: FocusMemory? = null,
    onMovieSelected: (movie: Movie) -> Unit = {}
) {
    ImmersiveListMoviesRow(
        movieList = movieList,
        modifier = modifier,
        itemDirection = itemDirection,
        startPadding = startPadding,
        endPadding = endPadding,
        title = title,
        titleStyle = titleStyle,
        showItemTitle = showItemTitle,
        showIndexOverImage = showIndexOverImage,
        onMovieSelected = onMovieSelected,
        focusMemory = focusMemory,
    )
}

/** [MoviesRow] that also reports the focused movie, e.g. for a backdrop behind the row. */
@Composable
fun ImmersiveListMoviesRow(
    movieList: MovieList,
    modifier: Modifier = Modifier,
    itemDirection: ItemDirection = ItemDirection.Vertical,
    startPadding: Dp = rememberChildPadding().start,
    endPadding: Dp = rememberChildPadding().end,
    title: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.headlineLarge.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp
    ),
    showItemTitle: Boolean = true,
    showIndexOverImage: Boolean = false,
    onMovieSelected: (Movie) -> Unit = {},
    onMovieFocused: (Movie) -> Unit = {},
    /** Remembers the focused card across leaving the screen (see [rememberFocus]). */
    focusMemory: FocusMemory? = null,
) {
    TileRow(
        items = movieList,
        key = { it.id },
        itemTitle = { it.name },
        itemImage = { movie, index, imageModifier ->
            MoviesRowItemImage(
                movie = movie,
                showIndexOverImage = showIndexOverImage,
                index = index,
                modifier = imageModifier,
            )
        },
        modifier = modifier,
        itemDirection = itemDirection,
        startPadding = startPadding,
        endPadding = endPadding,
        title = title,
        titleStyle = titleStyle,
        showItemTitle = showItemTitle,
        onItemSelected = onMovieSelected,
        onItemFocused = onMovieFocused,
        focusMemory = focusMemory,
    )
}

@Composable
private fun MoviesRowItemImage(
    movie: Movie,
    showIndexOverImage: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.CenterStart) {
        PosterImage(
            movie = movie,
            modifier = modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    if (showIndexOverImage) {
                        drawRect(
                            color = Color.Black.copy(
                                alpha = 0.1f
                            )
                        )
                    }
                },
        )
        movie.progress?.let { progress ->
            WatchProgressBar(
                progress = progress,
                modifier = Modifier
                    .matchParentSize()
                    .wrapContentHeight(Alignment.Bottom)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            )
        }
        if (showIndexOverImage) {
            Text(
                modifier = Modifier.padding(16.dp),
                text = "#${index.inc()}",
                style = MaterialTheme.typography.displayLarge
                    .copy(
                        shadow = Shadow(
                            offset = Offset(0.5f, 0.5f),
                            blurRadius = 5f
                        ),
                        color = Color.White
                    ),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** A thin bar along the bottom of a card showing how much of the title has been watched. */
@Composable
private fun WatchProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.35f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
