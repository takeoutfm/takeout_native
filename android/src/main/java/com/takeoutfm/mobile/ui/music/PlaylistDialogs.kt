package com.takeoutfm.mobile.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.main.MainViewModel

/** The "Add to playlist" picker, while one is open: the user's playlists, or a new one. */
@Composable
fun AddToPlaylistDialog(main: MainViewModel) {
    val target by main.adding.collectAsStateWithLifecycle()
    val playlists by main.playlists.collectAsStateWithLifecycle()
    if (target == null) return
    var creating by remember(target) { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.new_playlist),
            onConfirm = { main.addTo(null, it) },
            onDismiss = main::dismissAdd,
        )
        return
    }
    AlertDialog(
        onDismissRequest = main::dismissAdd,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            val list = playlists
            if (list == null) {
                CircularProgressIndicator()
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.new_playlist)) },
                            modifier = Modifier.clickable { creating = true },
                        )
                    }
                    items(list, key = { it.id }) { p ->
                        ListItem(
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text(pluralStringResource(R.plurals.track_count, p.trackCount, p.trackCount)) },
                            modifier = Modifier.clickable { main.addTo(p) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = main::dismissAdd) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Asks for a playlist name. */
@Composable
fun NameDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
