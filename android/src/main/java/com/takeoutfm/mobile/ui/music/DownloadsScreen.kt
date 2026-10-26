package com.takeoutfm.mobile.ui.music

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
import com.takeoutfm.media.offline.formatBytes
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.common.Art
import com.takeoutfm.mobile.ui.common.LoadingOrEmpty
import com.takeoutfm.mobile.ui.main.MainViewModel

/**
 * Downloads: what's on the device, by the album, playlist or podcast it came with; each group
 * plays offline and can be deleted, or everything at once ("frees N").
 */
@Composable
fun DownloadsScreen(main: MainViewModel, nav: MusicNav) {
    val downloads by main.downloads.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    val groups = downloads.values
        .groupBy { it.info.group.ifEmpty { it.item.album } }
        .values
        .sortedBy { it.first().info.groupTitle.ifEmpty { it.first().item.album }.lowercase() }
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
                Text(
                    stringResource(R.string.downloads_used, formatBytes(total)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            groups.forEach { group -> groupItems(group, main) }
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

private fun androidx.compose.foundation.lazy.LazyListScope.groupItems(group: List<DownloadedItem>, main: MainViewModel) {
    val first = group.first()
    val title = first.info.groupTitle.ifEmpty { first.item.album }
    val items = group.map { it.item }
    item(key = "g-${first.info.group}-$title") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(group.sumOf { it.bytes }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { main.playDownloads(title, items, 0) }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play_all)) }
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
                        CacheStatus.Cached -> listOf(d.item.artist, formatBytes(d.bytes)).filter { it.isNotEmpty() }.joinToString(" · ")
                        is CacheStatus.Partial -> stringResource(R.string.downloading) + " ${s.percent}%"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { DownloadBadge(d.status) },
            modifier = Modifier.clickable { main.playDownloads(title, items, group.indexOf(d)) },
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
