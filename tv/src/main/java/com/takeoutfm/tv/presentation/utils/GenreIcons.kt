package com.takeoutfm.tv.presentation.utils

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
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The Flutter app's icon for each TMDB movie genre; a film reel for any other. The phone app has
 * the same list (`ui/video/Genres.kt`); keep them in step.
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
