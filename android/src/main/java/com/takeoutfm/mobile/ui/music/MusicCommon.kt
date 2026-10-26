package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.shared.music.Album
import com.takeoutfm.shared.music.Artist
import com.takeoutfm.shared.music.Track

/** Where the music pages can go; each tab's NavHost provides one. */
class MusicNav(
    val openArtist: (String) -> Unit,
    val openAlbum: (String) -> Unit,
    val openQueue: (kind: String, id: String, title: String) -> Unit,
    val openPlaylists: () -> Unit,
    val openRecentTracks: () -> Unit,
    val openActivity: () -> Unit,
    val openPodcast: (String) -> Unit,
    val openSearch: () -> Unit,
    val back: () -> Unit,
)

/** A pushed page: a back arrow and title over [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { actions() },
            )
        },
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A horizontal row of album covers. */
@Composable
fun AlbumShelf(title: String, albums: List<Album>, onClick: (Album) -> Unit) {
    if (albums.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .width(140.dp)
                    .clickable { onClick(album) }
            ) {
                Art(album.coverUri, album.name, Modifier.size(140.dp))
                Text(album.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(
                    listOf(album.year, album.artist).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A horizontal row of artist portraits. */
@Composable
fun ArtistShelf(title: String, artists: List<Artist>, onClick: (Artist) -> Unit) {
    if (artists.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(artists, key = { it.id }) { artist ->
            Column(
                Modifier
                    .width(104.dp)
                    .clickable { onClick(artist) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Art(artist.imageUri, artist.name, Modifier.size(96.dp), RoundedCornerShape(50))
                Text(
                    artist.name,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * A track: its number (album pages) or cover, title and artist, a ♥ and a menu with Play next
 * and Add to queue.
 */
@Composable
fun TrackRow(
    track: Track,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onEnqueue: (last: Boolean) -> Unit,
    number: Int? = null,
    showAlbum: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            if (number != null) {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    Text("$number", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Art(track.coverUri, track.album, Modifier.size(48.dp).aspectRatio(1f), RoundedCornerShape(6.dp))
            }
        },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (showAlbum) listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · ") else track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.etag.isNotEmpty()) {
                    IconButton(onClick = { onFavorite(!favorite) }) {
                        Icon(
                            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = stringResource(if (favorite) R.string.unfavorite else R.string.favorite),
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.play_next)) }, onClick = { menu = false; onEnqueue(false) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = { menu = false; onEnqueue(true) })
                    }
                }
            }
        },
    )
}
