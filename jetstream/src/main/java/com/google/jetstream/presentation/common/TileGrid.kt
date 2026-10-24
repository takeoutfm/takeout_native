package com.google.jetstream.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.utils.focusOnInitialVisibility
import com.google.jetstream.presentation.utils.rememberFocus
import com.google.jetstream.presentation.utils.rememberFocusMemory
import com.google.jetstream.presentation.utils.restoreFocus

/**
 * A 6-column grid of cards for any kind of item: posters for the Movies and Shows tabs and genre
 * pages (via [PosterGrid]), and later album and artist tiles. The focused card shows its title.
 *
 * On the dashboard tabs it reports via [onScroll] whether it is scrolled to the top (so the
 * dashboard can show its top bar) and scrolls back up when [isTopBarVisible] turns true.
 * A standalone page can add a full-width [header] and start with the first item focused.
 * [itemImage] draws each card's image, already sized to [aspectRatio] by the modifier it's given.
 */
@Composable
fun <T> TileGrid(
    items: List<T>,
    key: (T) -> String,
    itemTitle: (T) -> String,
    itemImage: @Composable (item: T, modifier: Modifier) -> Unit,
    onItemClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float = ItemDirection.Vertical.aspectRatio,
    onScroll: (isTopBarVisible: Boolean) -> Unit = {},
    isTopBarVisible: Boolean = false,
    focusFirstItem: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val childPadding = rememberChildPadding()
    val gridState = rememberLazyGridState()
    val firstItemFocused = remember { mutableStateOf(false) }
    // Back from an opened item returns focus to its card, not the nearest one to the tab bar.
    val focusMemory = rememberFocusMemory()
    val shouldShowTopBar by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 &&
                gridState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(shouldShowTopBar) {
        onScroll(shouldShowTopBar)
    }
    LaunchedEffect(isTopBarVisible) {
        if (isTopBarVisible) gridState.animateScrollToItem(0)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(
            start = childPadding.start,
            end = childPadding.end,
            top = childPadding.top,
            bottom = 104.dp
        ),
        modifier = modifier.restoreFocus(focusMemory),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            TileGridItem(
                title = itemTitle(item),
                onClick = { onItemClick(item) },
                // Focus once the first item is actually laid out; requesting it earlier
                // (before the lazy grid places it) throws.
                modifier = Modifier
                    .rememberFocus(focusMemory, key(item))
                    .then(
                        if (focusFirstItem && index == 0) {
                            Modifier.focusOnInitialVisibility(firstItemFocused)
                        } else {
                            Modifier
                        }
                    )
            ) {
                itemImage(
                    item,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                )
            }
        }
    }
}

@Composable
private fun TileGridItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    image: @Composable () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    MovieCard(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        // Titles only show for the focused card, as in the rows.
        title = { TileTitle(text = title, isFocused = isFocused) },
    ) {
        image()
    }
}
