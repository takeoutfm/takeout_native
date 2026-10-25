package com.takeoutfm.tv.presentation.screens.music.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.takeoutfm.tv.presentation.theme.JetStreamButtonShape
import com.takeoutfm.tv.presentation.utils.ourColors

/**
 * A cover or photo; a muted colour shows while loading and when there's no image (many artists
 * and some albums have none), with [fallbackText] (e.g. the name) on it when there's no image.
 */
@Composable
fun MusicImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderKey: Any = url,
    fallbackText: String? = null,
) {
    val placeholder = remember(placeholderKey) { ourColors.random() }
    Box(modifier.background(placeholder), contentAlignment = Alignment.Center) {
        if (url.isEmpty()) {
            if (fallbackText != null) {
                // Darken the colour a little so white text reads on the pale ones too.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                )
                Text(
                    text = fallbackText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .crossfade(true)
                    .data(url)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** The details screens' button: filled for the main action, outlined for the others. */
@Composable
fun MusicButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(text = text, style = MaterialTheme.typography.titleSmall)
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            shape = ButtonDefaults.shape(shape = JetStreamButtonShape),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            shape = OutlinedButtonDefaults.shape(shape = JetStreamButtonShape),
            border = OutlinedButtonDefaults.border(
                border = Border(
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.border),
                    shape = JetStreamButtonShape
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
                    shape = JetStreamButtonShape
                ),
                pressedBorder = Border(
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
                    shape = JetStreamButtonShape
                ),
            ),
        ) { content() }
    }
}
