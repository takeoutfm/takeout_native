package com.takeoutfm.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** A grid tile: a square cover (music) or a 2:3 poster (movies, TV). */
data class Tile(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUri: String,
    val poster: Boolean = false,
)

/**
 * Tiles sized by a maximum width (more columns on wider screens, as the Flutter app's grids),
 * titles under the art.
 */
@Composable
fun TileGrid(
    tiles: List<Tile>,
    onClick: (Tile) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
) {
    val poster = tiles.firstOrNull()?.poster == true
    val minWidth: Dp = if (poster) 112.dp else 150.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minWidth),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(tiles, key = { it.id }) { tile -> TileItem(tile, onClick) }
    }
}

@Composable
fun TileItem(tile: Tile, onClick: (Tile) -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick(tile) }
    ) {
        Art(tile.imageUri, tile.title, Modifier.fillMaxWidth().aspectRatio(if (tile.poster) 2f / 3f else 1f))
        Text(
            tile.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (tile.subtitle.isNotEmpty()) {
            Text(
                tile.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Artwork, or the title on a tinted tile when there's none. */
@Composable
fun Art(uri: String, title: String, modifier: Modifier, shape: RoundedCornerShape = RoundedCornerShape(8.dp)) {
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (uri.isEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A loading spinner or, once loaded with nothing to show, [empty]. */
@Composable
fun LoadingOrEmpty(loading: Boolean, empty: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
