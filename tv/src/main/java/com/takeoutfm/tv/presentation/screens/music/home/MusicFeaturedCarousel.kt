package com.takeoutfm.tv.presentation.screens.music.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Carousel
import androidx.tv.material3.CarouselDefaults
import androidx.tv.material3.CarouselState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ShapeDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.takeoutfm.tv.R
import com.takeoutfm.shared.music.FeaturedAlbum
import com.takeoutfm.tv.presentation.screens.dashboard.rememberChildPadding
import com.takeoutfm.tv.presentation.screens.home.CarouselSaver
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.theme.JetStreamBorderWidth
import com.takeoutfm.tv.presentation.theme.JetStreamButtonShape
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import com.takeoutfm.tv.presentation.utils.handleDPadKeyEvents
import com.takeoutfm.tv.presentation.utils.returnFocusTarget

/**
 * Music Home's banner, like video Home's: new albums one at a time, each over its artist's
 * background with the cover beside the title. Select plays the album.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MusicFeaturedCarousel(
    albums: List<FeaturedAlbum>,
    onPlay: (FeaturedAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    val padding = rememberChildPadding()
    val carouselState = rememberSaveable(saver = CarouselSaver) { CarouselState(0) }
    var isFocused by remember { mutableStateOf(false) }

    Carousel(
        modifier = modifier
            .padding(start = padding.start, end = padding.start, top = padding.top)
            .border(
                width = JetStreamBorderWidth,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isFocused) 1f else 0f),
                shape = ShapeDefaults.Medium,
            )
            .clip(ShapeDefaults.Medium)
            .returnFocusTarget()
            .onFocusChanged { isFocused = it.hasFocus }
            .handleDPadKeyEvents(onEnter = {
                albums.getOrNull(carouselState.activeItemIndex)?.let(onPlay)
            }),
        itemCount = albums.size,
        carouselState = carouselState,
        carouselIndicator = { Indicator(albums.size, carouselState.activeItemIndex) },
        contentTransformStartToEnd = fadeIn(tween(durationMillis = 1000))
            .togetherWith(fadeOut(tween(durationMillis = 1000))),
        contentTransformEndToStart = fadeIn(tween(durationMillis = 1000))
            .togetherWith(fadeOut(tween(durationMillis = 1000))),
    ) { index ->
        val featured = albums[index]
        Background(featured, Modifier.fillMaxSize())
        Foreground(featured, isFocused, Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BoxScope.Indicator(count: Int, active: Int) {
    Box(
        Modifier
            .padding(32.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                ShapeDefaults.ExtraSmall,
            )
            .align(Alignment.BottomEnd)
    ) {
        CarouselDefaults.IndicatorRow(
            modifier = Modifier.padding(8.dp),
            itemCount = count,
            activeItemIndex = active,
        )
    }
}

/** The artist background, darkened towards the left for the text; else the cover, dimmed. */
@Composable
private fun Background(featured: FeaturedAlbum, modifier: Modifier) {
    Box(modifier) {
        if (featured.backgroundUri.isNotEmpty()) {
            AsyncImage(
                model = featured.backgroundUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MusicImage(
                url = featured.album.coverUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.35f),
                placeholderKey = featured.album.id,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.75f),
                        0.6f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    )
                )
        )
    }
}

@Composable
private fun Foreground(featured: FeaturedAlbum, isFocused: Boolean, modifier: Modifier) {
    val album = featured.album
    val shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(2f, 4f), 2f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(32.dp),
    ) {
        MusicImage(
            url = album.coverUri,
            contentDescription = album.name,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(JetStreamCardShape),
            placeholderKey = album.id,
            fallbackText = album.name,
        )
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(start = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.music_new_release),
                style = MaterialTheme.typography.labelLarge.copy(shadow = shadow),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Text(
                text = album.name,
                style = MaterialTheme.typography.displaySmall.copy(shadow = shadow),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = listOf(album.artist, album.year).filter { it.isNotEmpty() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.titleMedium.copy(shadow = shadow),
                maxLines = 1,
                modifier = Modifier.padding(top = 8.dp),
            )
            AnimatedVisibility(visible = isFocused) {
                Button(
                    onClick = {},
                    modifier = Modifier.padding(top = 12.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    shape = ButtonDefaults.shape(shape = JetStreamButtonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                        focusedContentColor = MaterialTheme.colorScheme.surface,
                    ),
                    scale = ButtonDefaults.scale(scale = 1f),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.music_play),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}
