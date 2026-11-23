package com.takeoutfm.tv.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape

/** The style of a description or biography, cut short on its page and whole in [FullTextDialog]. */
@Composable
fun LongTextStyle(): TextStyle = MaterialTheme.typography.titleSmall.copy(
    fontSize = 15.sp,
    fontWeight = FontWeight.Normal,
    lineHeight = 22.sp
)

/**
 * A description or biography cut to [maxLines] with an ellipsis. Only when it is cut can it take
 * focus, and selecting it calls [onClick] (to show [FullTextDialog]); otherwise it's plain text.
 */
@Composable
fun ExpandableText(
    text: String,
    maxLines: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    style: TextStyle = LongTextStyle(),
    alwaysFocusable: Boolean = false,
) {
    var isCut by remember(text) { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        enabled = isCut || alwaysFocusable,
        shape = ClickableSurfaceDefaults.shape(JetStreamCardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { isCut = it.hasVisualOverflow },
            modifier = Modifier.padding(8.dp).then(textModifier),
        )
    }
}

/** All of [text] under [title], one focusable paragraph at a time so the D-pad scrolls it. */
@Composable
fun FullTextDialog(title: String, text: String, onDismissRequest: () -> Unit) {
    val paragraphs = remember(text) {
        text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    val first = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = JetStreamCardShape,
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            // As tall as the text, up to most of the screen; longer text scrolls.
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )
                }
                itemsIndexed(paragraphs) { index, paragraph ->
                    Surface(
                        onClick = {},
                        shape = ClickableSurfaceDefaults.shape(JetStreamCardShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            focusedContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                    ) {
                        Text(
                            text = paragraph,
                            style = LongTextStyle(),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) { first.requestFocus() }
    }
}
