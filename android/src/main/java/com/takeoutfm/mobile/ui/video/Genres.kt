package com.takeoutfm.mobile.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CrisisAlert
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.takeoutfm.mobile.ui.common.Tile

/**
 * The Flutter app's icon for each TMDB movie genre; a film reel for any other. The TV app has
 * the same list (`presentation/utils/GenreIcons.kt`); keep them in step.
 */
fun genreIcon(name: String): ImageVector = when (name) {
    "Action" -> Icons.Filled.SportsMartialArts
    "Adventure" -> Icons.Filled.Explore
    "Animation" -> Icons.Filled.MovieFilter
    "Comedy" -> Icons.Filled.Mood
    "Crime" -> Icons.Filled.Gavel
    "Documentary" -> Icons.Filled.Videocam
    "Drama" -> Icons.Filled.TheaterComedy
    "Family" -> Icons.Filled.Diversity3
    "Fantasy" -> Icons.Filled.AutoFixHigh
    "History" -> Icons.Filled.HistoryEdu
    "Horror" -> Icons.Filled.DarkMode
    "Music" -> Icons.Filled.MusicNote
    "Mystery" -> Icons.Filled.Fingerprint
    "Romance" -> Icons.Filled.Favorite
    "Science Fiction" -> Icons.Filled.RocketLaunch
    "TV Movie" -> Icons.Filled.Tv
    "Thriller" -> Icons.Filled.CrisisAlert
    "War" -> Icons.Filled.MilitaryTech
    "Western" -> Icons.Filled.Landscape
    else -> Icons.Filled.Movie
}

/** Home's Genres: a card per genre with its icon and name, as in the Flutter app. */
@Composable
fun GenreGrid(genres: List<Tile>, onClick: (Tile) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(genres, key = { it.id }) { genre -> GenreCard(genre.title) { onClick(genre) } }
    }
}

@Composable
private fun GenreCard(name: String, onClick: () -> Unit) {
    val content = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .aspectRatio(1.2f)
            .clip(RoundedCornerShape(16.dp))
            .background(content.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        Icon(genreIcon(name), contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
