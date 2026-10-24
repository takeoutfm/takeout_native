package com.google.jetstream.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.jetstream.data.entities.Movie
import com.google.jetstream.data.entities.MovieList

/** A [TileGrid] of movie and show posters: the Movies and Shows tabs and the genre pages. */
@Composable
fun PosterGrid(
    movieList: MovieList,
    onMovieClick: (movie: Movie) -> Unit,
    modifier: Modifier = Modifier,
    onScroll: (isTopBarVisible: Boolean) -> Unit = {},
    isTopBarVisible: Boolean = false,
    focusFirstItem: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    TileGrid(
        items = movieList,
        key = { it.id },
        itemTitle = { it.name },
        itemImage = { movie, imageModifier ->
            PosterImage(movie = movie, modifier = imageModifier)
        },
        onItemClick = onMovieClick,
        modifier = modifier,
        onScroll = onScroll,
        isTopBarVisible = isTopBarVisible,
        focusFirstItem = focusFirstItem,
        header = header,
    )
}
