package com.google.jetstream.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.google.jetstream.presentation.screens.dashboard.rememberChildPadding
import com.google.jetstream.presentation.theme.JetStreamCardShape
import kotlinx.coroutines.delay

/** How long a load must take before any loading state appears, so quick loads don't flash. */
private const val LoadingDelayMs = 300L

/** Shows [content] only once it has been composed for [LoadingDelayMs], then fades it in. */
@Composable
fun DelayedLoading(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LoadingDelayMs)
        visible = true
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), modifier = modifier) { content() }
}

/** A placeholder block that gently pulses; the building block of the skeleton screens. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = JetStreamCardShape) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    Box(
        modifier
            .graphicsLayer { alpha = pulse }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** A line of text that hasn't loaded yet. */
@Composable
fun SkeletonText(width: Dp, height: Dp = 16.dp, modifier: Modifier = Modifier) {
    SkeletonBlock(modifier.width(width).height(height), RoundedCornerShape(height / 2))
}

/** A spinning ring, for the player while a title loads or buffers. */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = Color.White) {
    val angle by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "spinnerAngle"
    )
    Canvas(modifier.size(56.dp)) {
        val stroke = 5.dp.toPx()
        drawArc(
            color = color.copy(alpha = 0.2f),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            style = Stroke(stroke)
        )
        drawArc(
            color = color,
            startAngle = angle, sweepAngle = 90f, useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

// ---- Screen skeletons ----

/** A row title and a row of cards, like [MoviesRow]. */
@Composable
fun SkeletonRow(
    cardWidth: Dp,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    count: Int = 7,
) {
    val childPadding = rememberChildPadding()
    Column(modifier.padding(start = childPadding.start, top = 16.dp)) {
        SkeletonText(width = 220.dp, height = 24.dp, modifier = Modifier.padding(vertical = 16.dp))
        // Unbounded so cards past the screen edge keep their size instead of being squeezed.
        Row(
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            repeat(count) {
                SkeletonBlock(Modifier.width(cardWidth).height(cardWidth / aspectRatio))
            }
        }
    }
}

/** Home: the featured banner, then rows of landscape and poster cards. */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    val childPadding = rememberChildPadding()
    DelayedLoading(modifier) {
        Column(Modifier.fillMaxSize().clipToBounds().wrapContentHeight(Alignment.Top, unbounded = true)) {
            SkeletonBlock(
                Modifier
                    .padding(horizontal = childPadding.start)
                    .fillMaxWidth()
                    .height(324.dp)
            )
            SkeletonRow(cardWidth = ItemDirection.Horizontal.cardWidth, aspectRatio = 16f / 9f)
            SkeletonRow(cardWidth = ItemDirection.Vertical.cardWidth, aspectRatio = 10.5f / 16f)
        }
    }
}

/** Music Home: rows of square album covers. */
@Composable
fun MusicHomeSkeleton(modifier: Modifier = Modifier) {
    DelayedLoading(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .wrapContentHeight(Alignment.Top, unbounded = true)
        ) {
            SkeletonRow(cardWidth = ItemDirection.Square.cardWidth, aspectRatio = 1f)
            SkeletonRow(cardWidth = ItemDirection.Square.cardWidth, aspectRatio = 1f)
        }
    }
}

/** Movies, Shows, Favorites and genre pages (posters), Artists (squares): a 6-column grid. */
@Composable
fun PosterGridSkeleton(
    modifier: Modifier = Modifier,
    showHeader: Boolean = false,
    aspectRatio: Float = 10.5f / 16f,
) {
    val childPadding = rememberChildPadding()
    DelayedLoading(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = childPadding.start)
                .padding(top = childPadding.top),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (showHeader) {
                SkeletonText(
                    width = 280.dp,
                    height = 40.dp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = childPadding.top * 2)
                )
            }
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    repeat(6) {
                        SkeletonBlock(Modifier.weight(1f).aspectRatio(aspectRatio))
                    }
                }
            }
        }
    }
}

/** Genres: a 4-column grid of wide tiles. */
@Composable
fun TileGridSkeleton(modifier: Modifier = Modifier, columns: Int = 4) {
    val childPadding = rememberChildPadding()
    DelayedLoading(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = childPadding.start)
                .padding(top = childPadding.top)
        ) {
            repeat(4) {
                Row {
                    repeat(columns) {
                        SkeletonBlock(
                            Modifier
                                .weight(1f)
                                .padding(8.dp)
                                .aspectRatio(16f / 9f)
                        )
                    }
                }
            }
        }
    }
}

/** Movie/series details: title, description, metadata, credits and buttons, then a row. */
@Composable
fun DetailsSkeleton(modifier: Modifier = Modifier) {
    val childPadding = rememberChildPadding()
    DelayedLoading(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .padding(start = childPadding.start)
        ) {
            Spacer(Modifier.height(108.dp))
            SkeletonText(width = 420.dp, height = 56.dp)
            Spacer(Modifier.height(20.dp))
            SkeletonText(width = 820.dp)
            Spacer(Modifier.height(10.dp))
            SkeletonText(width = 640.dp)
            Spacer(Modifier.height(28.dp))
            SkeletonText(width = 360.dp)
            Spacer(Modifier.height(36.dp))
            SkeletonText(width = 80.dp, height = 14.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonText(width = 200.dp)
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonBlock(Modifier.width(200.dp).height(56.dp))
                SkeletonBlock(Modifier.width(220.dp).height(56.dp))
            }
            Spacer(Modifier.height(48.dp))
            Row(
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                repeat(7) { SkeletonBlock(Modifier.width(144.dp).height(259.dp)) }
            }
        }
    }
}

/** Person page: photo beside name and bio, then a poster row. */
@Composable
fun PersonSkeleton(modifier: Modifier = Modifier) {
    val childPadding = rememberChildPadding()
    DelayedLoading(modifier) {
        Column(Modifier.fillMaxSize().clipToBounds().wrapContentHeight(Alignment.Top, unbounded = true)) {
            Row(
                Modifier
                    .padding(horizontal = childPadding.start)
                    .padding(top = 72.dp, bottom = 16.dp)
            ) {
                SkeletonBlock(Modifier.width(200.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.width(40.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonText(width = 380.dp, height = 52.dp)
                    SkeletonText(width = 300.dp, height = 20.dp)
                    Spacer(Modifier.height(8.dp))
                    repeat(5) { SkeletonText(width = if (it == 4) 520.dp else 900.dp) }
                }
            }
            SkeletonRow(cardWidth = ItemDirection.Vertical.cardWidth, aspectRatio = 10.5f / 16f)
        }
    }
}

/** The player before a title starts: black, with a spinner. */
@Composable
fun PlayerLoading(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        DelayedLoading { Spinner() }
    }
}
