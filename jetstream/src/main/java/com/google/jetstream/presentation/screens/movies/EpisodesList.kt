package com.google.jetstream.presentation.screens.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.jetstream.R
import com.google.jetstream.data.entities.Episode
import com.google.jetstream.data.entities.Season
import com.google.jetstream.presentation.common.MovieCard
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.utils.FocusMemory
import com.google.jetstream.presentation.utils.rememberFocus

/** One row of episode cards for [season]. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeasonEpisodesRow(
    season: Season,
    focusMemory: FocusMemory? = null,
    onEpisodeSelected: (Episode) -> Unit,
) {
    val childPadding = rememberChildPadding()

    Column(
        modifier = Modifier.padding(top = childPadding.top),
    ) {
        Text(
            text = if (season.number == 0) {
                stringResource(R.string.episodes_specials)
            } else {
                stringResource(R.string.episodes_season, season.number)
            },
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp
            ),
            modifier = Modifier.padding(start = childPadding.start)
        )
        LazyRow(
            modifier = Modifier
                .padding(top = 16.dp)
                .focusRestorer(),
            contentPadding = PaddingValues(start = childPadding.start, end = childPadding.end)
        ) {
            items(season.episodes, key = { it.id }) { episode ->
                EpisodeItem(
                    episode = episode,
                    onClick = { onEpisodeSelected(episode) },
                    modifier = Modifier
                        .width(268.dp)
                        .padding(end = 20.dp, bottom = 16.dp)
                        .then(
                            if (focusMemory != null) {
                                Modifier.rememberFocus(focusMemory, "episode-${episode.id}")
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun EpisodeItem(
    episode: Episode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MovieCard(
        onClick = onClick,
        modifier = modifier,
        title = {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    text = "${episode.episode}. ${episode.name}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = episode.duration,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.75f)
                )
            }
        },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .crossfade(true)
                .data(episode.stillUri.ifEmpty { null })
                .build(),
            contentDescription = episode.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}
