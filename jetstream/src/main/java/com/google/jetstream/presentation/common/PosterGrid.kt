package com.google.jetstream.presentation.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.jetstream.data.entities.Movie
import com.google.jetstream.data.entities.MovieList
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding

/**
 * A whole library as a 6-column poster grid, for the Movies and Shows tabs.
 *
 * Like the tabs' original lists, it reports via [onScroll] whether it is scrolled to the top
 * (so the dashboard can show its top bar) and scrolls back up when [isTopBarVisible] turns true.
 */
@Composable
fun PosterGrid(
    movieList: MovieList,
    onMovieClick: (movie: Movie) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val childPadding = rememberChildPadding()
    val gridState = rememberLazyGridState()
    val shouldShowTopBar by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 &&
                gridState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(shouldShowTopBar) {
        onScroll(shouldShowTopBar)
    }
    LaunchedEffect(isTopBarVisible) {
        if (isTopBarVisible) gridState.animateScrollToItem(0)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(
            start = childPadding.start,
            end = childPadding.end,
            top = childPadding.top,
            bottom = 104.dp
        ),
        modifier = modifier,
    ) {
        items(movieList, key = { it.id }) { movie ->
            PosterGridItem(movie = movie, onClick = { onMovieClick(movie) })
        }
    }
}

@Composable
private fun PosterGridItem(movie: Movie, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    // Titles only show for the focused poster, as in the rows.
    val titleAlpha by animateFloatAsState(if (isFocused) 1f else 0f, label = "")

    MovieCard(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
        title = {
            Text(
                text = movie.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .alpha(titleAlpha)
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        },
    ) {
        PosterImage(
            movie = movie,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ItemDirection.Vertical.aspectRatio)
        )
    }
}
