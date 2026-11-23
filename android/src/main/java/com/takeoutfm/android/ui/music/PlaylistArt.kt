package com.takeoutfm.android.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takeoutfm.android.R

private val ArtShape = RoundedCornerShape(8.dp)

/**
 * A playlist's art, as on the Mac: four album covers in a square, or the first alone when it
 * has fewer; a placeholder with none (an empty playlist, or while they load).
 */
@Composable
fun PlaylistMosaic(covers: List<String>, modifier: Modifier) {
    Box(
        modifier.clip(ArtShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            covers.size >= 4 -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) { Tile(covers[0]); Tile(covers[1]) }
                Row(Modifier.weight(1f).fillMaxWidth()) { Tile(covers[2]); Tile(covers[3]) }
            }
            covers.isNotEmpty() -> Cover(covers[0], Modifier.fillMaxSize())
            else -> Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(uri: String) =
    Cover(uri, Modifier.weight(1f).fillMaxHeight())

// Decorative: the playlist's name is always beside it.
@Composable
private fun Cover(uri: String, modifier: Modifier) =
    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)

/** Favorite Tracks' art: a heart on a warm gradient, set apart from the playlists' covers. */
@Composable
fun FavoriteTracksArt(modifier: Modifier) {
    Box(
        modifier.clip(ArtShape).background(Brush.linearGradient(listOf(Color(0xFFFF2D55), Color(0xFFFF3B30)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
    }
}

/** Asks before deleting the playlist [name]: it goes from the server, for every app. */
@Composable
fun DeletePlaylistDialog(name: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_playlist_question, name.trim())) },
        text = { Text(stringResource(R.string.delete_playlist_detail)) },
        confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
