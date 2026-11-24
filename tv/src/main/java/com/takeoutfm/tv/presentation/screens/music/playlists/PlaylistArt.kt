package com.takeoutfm.tv.presentation.screens.music.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage

/**
 * A playlist's art, as on the Mac, phone and Linux: four album covers in a square, or the first
 * alone when it has fewer; a placeholder with none (an empty playlist, or while they load).
 */
@Composable
fun PlaylistMosaic(covers: List<String>, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            covers.size >= 4 -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) { Tile(covers[0]); Tile(covers[1]) }
                Row(Modifier.weight(1f).fillMaxWidth()) { Tile(covers[2]); Tile(covers[3]) }
            }
            covers.isNotEmpty() -> Cover(covers[0], Modifier.fillMaxSize())
            else -> Symbol(Icons.AutoMirrored.Filled.QueueMusic)
        }
    }
}

@Composable
private fun RowScope.Tile(uri: String) = Cover(uri, Modifier.weight(1f).fillMaxHeight())

// Decorative: the playlist's name is always beside it.
@Composable
private fun Cover(uri: String, modifier: Modifier) =
    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)

@Composable
private fun Symbol(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(64.dp))

/** Favorite Tracks' art: a heart on a warm gradient, set apart from the playlists' covers. */
@Composable
fun FavoriteTracksArt(modifier: Modifier) {
    Box(
        modifier.background(Brush.linearGradient(listOf(Color(0xFFFF2D55), Color(0xFFFF3B30)))),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(Icons.Filled.Favorite, tint = Color.White)
    }
}

/** The New Playlist tile's art: a plus. */
@Composable
fun NewPlaylistArt(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Symbol(Icons.Filled.Add)
    }
}
