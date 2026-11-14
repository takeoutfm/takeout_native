package com.takeoutfm.android.ui.music

import androidx.compose.material.icons.filled.Done
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.media.offline.CacheStatus
import com.takeoutfm.media.offline.DownloadedItem
import com.takeoutfm.android.R
import com.takeoutfm.android.ui.common.Art
import com.takeoutfm.android.ui.common.LoadingOrEmpty
import com.takeoutfm.android.ui.main.MainViewModel
import com.takeoutfm.shared.util.formatBytes
import com.takeoutfm.android.ui.video.VideoPlayerActivity
import androidx.compose.ui.platform.LocalContext

/**
 * Downloads: what's on the device, by the album, playlist or podcast it came with, the largest
 * first; each group plays offline and can be deleted, or everything at once ("frees N"). What
 * Playback Caching kept is marked Cached (it may be removed to stay within the cache size); a
 * warning shows when the viewer's downloads leave little space.
 */
@Composable
fun DownloadsScreen(main: MainViewModel, nav: MusicNav) {
    val downloads by main.downloads.collectAsStateWithLifecycle()
    val usage by main.cacheUsage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { main.refreshCacheUsage() }
    val context = LocalContext.current
    var confirmAll by remember { mutableStateOf(false) }
    val groups = downloads.values
        .groupBy { it.info.group.ifEmpty { it.item.album } }
        .values
        .sortedByDescending { group -> group.sumOf { it.bytes } }
    val total = downloads.values.sumOf { it.bytes }
    MusicPage(
        title = stringResource(R.string.downloads),
        onBack = nav.back,
        actions = {
            if (downloads.isNotEmpty()) {
                IconButton(onClick = { confirmAll = true }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.delete_all_downloads))
                }
            }
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            LoadingOrEmpty(false, stringResource(R.string.no_downloads), Modifier.padding(padding))
            return@MusicPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                val cached = downloads.values.filter { it.info.cached }.sumOf { it.bytes }
                Text(
                    if (cached > 0) {
                        stringResource(R.string.downloads_used_cached, formatBytes(total - cached), formatBytes(cached))
                    } else {
                        stringResource(R.string.downloads_used, formatBytes(total))
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val watched = usage?.watched.orEmpty().mapNotNull { downloads[it] }
            if (watched.isNotEmpty()) {
                item {
                    // Watched movies and episodes, removed only when asked.
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Done, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.watched_downloads, watched.size, formatBytes(watched.sumOf { it.bytes }))) },
                        trailingContent = {
                            TextButton(onClick = { main.removeDownloads(watched.map { it.item.etag }) }) {
                                Text(stringResource(R.string.remove_download))
                            }
                        },
                    )
                }
            }
            if (usage?.lowSpace == true) {
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text(stringResource(R.string.low_space_title)) },
                        supportingContent = { Text(stringResource(R.string.low_space_downloads)) },
                    )
                }
            }
            groups.forEach { group -> groupItems(group, main, context) }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            text = { Text(stringResource(R.string.delete_all_question, formatBytes(total))) },
            confirmButton = {
                TextButton(onClick = { main.removeDownloads(downloads.keys); confirmAll = false }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.groupItems(
    group: List<DownloadedItem>,
    main: MainViewModel,
    context: android.content.Context,
) {
    val first = group.first()
    val title = first.info.groupTitle.ifEmpty { first.item.album }
    val items = group.map { it.item }
    // Movies and episodes open the video player one at a time.
    val video = first.info.videoId.isNotEmpty()
    item(key = "g-${first.info.group}-$title") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(group.sumOf { it.bytes }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!video) IconButton(onClick = { main.playDownloads(title, items, 0) }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play_all)) }
            IconButton(onClick = { main.removeDownloads(group.map { it.item.etag }) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_download)) }
        }
    }
    items(group, key = { it.item.etag }) { d ->
        ListItem(
            leadingContent = { Art(d.item.imageUri, d.item.album, Modifier.size(44.dp), RoundedCornerShape(6.dp)) },
            headlineContent = { Text(d.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    when (val s = d.status) {
                        CacheStatus.Cached -> listOf(
                            if (d.info.cached) stringResource(R.string.cached) else "",
                            d.item.artist,
                            formatBytes(d.bytes),
                        ).filter { it.isNotEmpty() }.joinToString(" · ")
                        is CacheStatus.Partial -> stringResource(R.string.downloading) + " ${s.percent}%"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { DownloadBadge(d.status) },
            modifier = Modifier.clickable {
                if (d.info.videoId.isNotEmpty()) {
                    VideoPlayerActivity.start(context, d.info.videoId, startOver = false)
                } else {
                    main.playDownloads(title, items, group.indexOf(d))
                }
            },
        )
    }
}

/** ✓ for a track on the device, a progress ring while it downloads, nothing otherwise. */
@Composable
fun DownloadBadge(status: CacheStatus?, modifier: Modifier = Modifier) {
    when (status) {
        CacheStatus.Cached -> Icon(
            Icons.Filled.DownloadDone,
            contentDescription = stringResource(R.string.downloaded),
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(20.dp),
        )
        is CacheStatus.Partial -> androidx.compose.material3.CircularProgressIndicator(
            progress = { status.percent / 100f },
            strokeWidth = 2.dp,
            modifier = modifier.size(18.dp),
        )
        null -> Unit
    }
}
