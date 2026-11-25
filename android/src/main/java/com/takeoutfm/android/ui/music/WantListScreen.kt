package com.takeoutfm.android.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil.compose.AsyncImage
import com.takeoutfm.android.R
import com.takeoutfm.android.ui.common.LoadingOrEmpty
import com.takeoutfm.android.ui.main.WantListRoute
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.music.WantedRelease
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class WantListViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val music: TakeoutMusicRepository,
) : ViewModel() {
    private val route = saved.toRoute<WantListRoute>()
    val artistName = route.name
    private val _state = MutableStateFlow<Load<List<WantedRelease>>>(Load.Loading)
    val state: StateFlow<Load<List<WantedRelease>>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                Load.Loaded(music.wantList(route.artistId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed
            }
        }
    }
}

/**
 * An artist's Want List (the server's /api/artists/{id}/wantlist): the releases the library
 * doesn't have, newest first, each with where to look for it, as the Flutter app showed them:
 * Amazon and CamelCamelCamel (when MusicBrainz has an ASIN), MusicBrainz and a web search.
 * Selecting a release opens Amazon, else MusicBrainz.
 */
@Composable
fun WantListScreen(nav: MusicNav, viewModel: WantListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MusicPage(title = viewModel.artistName, onBack = nav.back) { padding ->
        val releases = (state as? Load.Loaded)?.value
        if (releases.isNullOrEmpty()) {
            LoadingOrEmpty(
                state is Load.Loading,
                stringResource(if (state is Load.Failed) R.string.cant_load else R.string.want_list_empty),
                Modifier.padding(padding),
            )
            return@MusicPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { SectionTitle(stringResource(R.string.want_list)) }
            items(releases, key = { it.musicBrainzUri.ifEmpty { it.name + it.year } }) { release ->
                WantedReleaseCard(release)
            }
            item { Spacer(Modifier.height(96.dp)) } // clear of the play button
        }
    }
}

/**
 * A wanted release: its cover (or a placeholder), name and year · type · country, then where to
 * look for it as a row of text links across the card (wrapping on narrow screens).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WantedReleaseCard(release: WantedRelease) {
    val uri = LocalUriHandler.current
    val open = { link: String -> runCatching { uri.openUri(link) } }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open(release.amazonUri.ifEmpty { release.musicBrainzUri.ifEmpty { release.searchUri } }) }
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                // drawn over the placeholder; a release without a cover leaves the icon
                AsyncImage(
                    model = release.coverUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(release.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(release.year, release.type, release.country).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // where to look for it: one row of text links across the card (wrapping on narrow screens)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            @Composable
            fun Link(label: Int, link: String) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { open(link) }
                        .padding(vertical = 6.dp),
                )
            }
            if (release.amazonUri.isNotEmpty()) {
                Link(R.string.want_amazon, release.amazonUri)
                Link(R.string.want_camel, release.camelUri)
            }
            if (release.musicBrainzUri.isNotEmpty()) Link(R.string.want_musicbrainz, release.musicBrainzUri)
            Link(R.string.want_search, release.searchUri)
        }
    }
}
