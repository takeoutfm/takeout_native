package com.google.jetstream.presentation.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding

/**
 * A titled row of cards for any kind of item (movies, and later albums, artists, stations).
 * Fixed-width cards; the focused card shows its title underneath; focus comes back to the card
 * that was opened, or the first one. [itemImage] draws each card's image, already sized to
 * [itemDirection]'s aspect ratio by the modifier it's given.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T> TileRow(
    items: List<T>,
    key: (T) -> String,
    itemTitle: (T) -> String,
    itemImage: @Composable (item: T, index: Int, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    itemDirection: ItemDirection = ItemDirection.Vertical,
    startPadding: Dp = rememberChildPadding().start,
    endPadding: Dp = rememberChildPadding().end,
    title: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.headlineLarge.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp
    ),
    showItemTitle: Boolean = true,
    onItemSelected: (T) -> Unit = {},
    onItemFocused: (T) -> Unit = {},
) {
    val (lazyRow, firstItem) = remember { FocusRequester.createRefs() }

    Column(
        modifier = modifier.focusGroup()
    ) {
        if (title != null) {
            Text(
                text = title,
                style = titleStyle,
                modifier = Modifier
                    .alpha(1f)
                    .padding(start = startPadding, top = 16.dp, bottom = 16.dp)
            )
        }
        AnimatedContent(
            targetState = items,
            label = "",
        ) { itemsState ->
            LazyRow(
                contentPadding = PaddingValues(
                    start = startPadding,
                    end = endPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .focusRequester(lazyRow)
                    .focusRestorer {
                        firstItem
                    }
            ) {
                itemsIndexed(itemsState, key = { _, item -> key(item) }) { index, item ->
                    val itemModifier = if (index == 0) {
                        Modifier.focusRequester(firstItem)
                    } else {
                        Modifier
                    }
                    TileRowItem(
                        modifier = itemModifier.weight(1f),
                        index = index,
                        item = item,
                        itemTitle = itemTitle,
                        itemImage = itemImage,
                        itemDirection = itemDirection,
                        showItemTitle = showItemTitle,
                        onItemSelected = {
                            lazyRow.saveFocusedChild()
                            onItemSelected(it)
                        },
                        onItemFocused = onItemFocused,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun <T> TileRowItem(
    index: Int,
    item: T,
    itemTitle: (T) -> String,
    itemImage: @Composable (item: T, index: Int, modifier: Modifier) -> Unit,
    itemDirection: ItemDirection,
    showItemTitle: Boolean,
    onItemSelected: (T) -> Unit,
    onItemFocused: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    MovieCard(
        onClick = { onItemSelected(item) },
        title = {
            if (showItemTitle) {
                TileTitle(text = itemTitle(item), isFocused = isFocused)
            }
        },
        modifier = Modifier
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    onItemFocused(item)
                }
            }
            .focusProperties {
                left = if (index == 0) {
                    FocusRequester.Cancel
                } else {
                    FocusRequester.Default
                }
            }
            .then(modifier)
            .width(itemDirection.cardWidth)
    ) {
        itemImage(item, index, Modifier.aspectRatio(itemDirection.aspectRatio))
    }
}

/** A card's title under the image, shown only while the card has focus. */
@Composable
fun TileTitle(text: String, isFocused: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        label = "",
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        textAlign = TextAlign.Center,
        modifier = modifier
            .alpha(alpha)
            .fillMaxWidth()
            .padding(top = 4.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
