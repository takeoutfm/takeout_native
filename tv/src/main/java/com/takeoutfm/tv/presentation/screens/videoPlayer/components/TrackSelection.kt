package com.takeoutfm.tv.presentation.screens.videoPlayer.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.takeoutfm.media.video.TrackOption
import com.takeoutfm.tv.R
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import java.util.Locale

@Composable
fun TrackPickerDialog(
    title: String,
    options: List<TrackOption>,
    emptyText: String,
    onSelect: (TrackOption) -> Unit,
    onDismissRequest: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = JetStreamCardShape,
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(min = 360.dp, max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 8.dp, bottom = 16.dp)
                )
                if (options.isEmpty()) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                // Start focus on the current choice so the D-pad lands where the user is.
                val focusIndex = options.indexOfFirst { it.isSelected }.coerceAtLeast(0)
                val focusRequester = remember { FocusRequester() }
                LazyColumn {
                    itemsIndexed(options) { index, option ->
                        ListItem(
                            selected = option.isSelected,
                            enabled = option.isSupported,
                            onClick = { onSelect(option) },
                            headlineContent = { Text(option.label) },
                            supportingContent = if (option.isSupported) {
                                null
                            } else {
                                { Text(stringResource(R.string.track_not_supported)) }
                            },
                            trailingContent = if (option.isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (index == focusIndex) {
                                        Modifier.focusRequester(focusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
                if (options.isNotEmpty()) {
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                }
            }
        }
    }
}

/** Renders the player's current subtitle cues, text and bitmap alike. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerSubtitles(player: Player, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember {
        SubtitleView(context).apply {
            setStyle(
                CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK,
                    null
                )
            )
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                view.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        view.setCues(player.currentCues.cues)
        onDispose { player.removeListener(listener) }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
