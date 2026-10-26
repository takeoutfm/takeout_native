package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.main.MainViewModel
import com.takeoutfm.shared.music.Station
import com.takeoutfm.shared.music.StationKind

/**
 * Radio, short enough to take in at a glance: recently played stations and live streams as
 * artwork rows, then genres, decades and the rest as coloured name tiles (as on the TV) in a
 * grid, rather than one long list.
 */
@Composable
fun RadioScreen(main: MainViewModel, viewModel: MusicTabsViewModel = hiltViewModel()) {
    val page by viewModel.radio.collectAsStateWithLifecycle(initialValue = null)
    TabPage(title = stringResource(R.string.nav_radio)) { padding ->
        val p = page
        if (p == null || p.groups.isEmpty()) {
            LoadingOrEmpty(p == null, stringResource(R.string.nothing_here), Modifier.padding(padding))
            return@TabPage
        }
        // Kinds whose tiles are all shown; the rest show their first rows.
        var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // As GridCells.Adaptive works it out, so a section's first rows are full.
            val columns = ((maxWidth - 32.dp + TileSpacing) / (TileMinWidth + TileSpacing)).toInt().coerceAtLeast(1)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TileMinWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(TileSpacing),
                verticalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                if (p.recent.isNotEmpty()) {
                    header("recent", R.string.recently_played)
                    artworkRow("recent", p.recent, main::playStation)
                }
                p.groups.forEach { group ->
                    header(group.kind.name, group.kind.label())
                    if (group.kind == StationKind.Stream) {
                        artworkRow("streams", group.stations, main::playStation)
                    } else {
                        val key = group.kind.name
                        val limit = columns * CollapsedRows
                        val open = key in expanded
                        items(if (open) group.stations else group.stations.take(limit), key = { it.id }) { station ->
                            NameTile(station) { main.playStation(station.id) }
                        }
                        if (group.stations.size > limit) {
                            item(key = "more-$key", span = { GridItemSpan(maxLineSpan) }) {
                                TextButton(onClick = { expanded = if (open) expanded - key else expanded + key }) {
                                    Text(
                                        if (open) stringResource(R.string.show_less)
                                        else stringResource(R.string.show_all, group.stations.size),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TileMinWidth = 150.dp
private val TileSpacing = 8.dp
private const val CollapsedRows = 4

private fun LazyGridScope.header(key: String, title: Int) {
    item(key = "header-$key", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
    }
}

/** A full-width row of station artwork with names, scrolling sideways. */
private fun LazyGridScope.artworkRow(key: String, stations: List<Station>, play: (String) -> Unit) {
    item(key = "row-$key", span = { GridItemSpan(maxLineSpan) }) {
        // Bleeds to the screen edges so the row scrolls under them, aligned with the grid.
        LazyRow(
            modifier = Modifier.bleed(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(stations, key = { it.id }) { station ->
                Column(Modifier.width(112.dp).clickable { play(station.id) }) {
                    Art(station.imageUri, station.name, Modifier.size(112.dp), RoundedCornerShape(8.dp))
                    Text(
                        station.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Widens the content by [edge] on each side, past the grid's padding to the screen edges. */
private fun Modifier.bleed(edge: Dp) = layout { measurable, constraints ->
    val extra = (edge * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** A genre, decade or other station: its name on a colour picked from its id. */
@Composable
private fun NameTile(station: Station, onClick: () -> Unit) {
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(TileColors[Math.floorMod(station.id.hashCode(), TileColors.size)])
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            station.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.Black.copy(alpha = 0.87f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Material 300 tones (as the TV's placeholders): dark text reads on all of them. */
private val TileColors = listOf(
    Color(0xFFE57373), Color(0xFFF06292), Color(0xFFBA68C8), Color(0xFF9575CD),
    Color(0xFF7986CB), Color(0xFF64B5F6), Color(0xFF4FC3F7), Color(0xFF4DD0E1),
    Color(0xFF4DB6AC), Color(0xFF81C784), Color(0xFFAED581), Color(0xFFDCE775),
    Color(0xFFFFD54F), Color(0xFFFFB74D), Color(0xFFFF8A65), Color(0xFFA1887F),
)

private fun StationKind.label(): Int = when (this) {
    StationKind.Stream -> R.string.radio_streams
    StationKind.Genre -> R.string.radio_genres
    StationKind.Period -> R.string.radio_decades
    StationKind.Artist -> R.string.radio_artists
    StationKind.Similar -> R.string.radio_similar
    StationKind.Series -> R.string.radio_series
    StationKind.Other -> R.string.radio_other
}
