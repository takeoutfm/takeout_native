/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.takeoutfm.tv.presentation.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.takeoutfm.tv.R
import com.takeoutfm.tv.data.entities.Recommendation
import com.takeoutfm.shared.library.LibraryIndex
import com.takeoutfm.tv.data.takeout.LibraryMode
import com.takeoutfm.tv.data.util.StringConstants
import com.takeoutfm.tv.presentation.screens.Screens
import com.takeoutfm.tv.presentation.theme.IconSize
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import com.takeoutfm.tv.presentation.theme.LexendExa
import com.takeoutfm.tv.presentation.utils.occupyScreenSize

/** A top bar tab: one of the fixed [Screens] tabs, or one of the server's recommendation sets. */
@Immutable
data class DashboardTab(
    val route: String,
    val label: String,
    val icon: ImageVector? = null,
)

/** Route of the page for the [index]th recommendation set. */
fun recommendedRoute(index: Int) = "$RecommendedRouteName/$index"
const val RecommendedRouteName = "Recommended"
const val RecommendedIndexKey = "index"
const val RecommendedRoutePattern = "$RecommendedRouteName/{$RecommendedIndexKey}"

/**
 * Video: Home, then a tab per recommendation set, then Genres, Movies, Shows, Favorites, Search.
 * Music: Home, Artists, Favorites, Playlists (when the user has any), Radio, Podcasts (when the
 * server has any).
 */
fun dashboardTabs(
    recommendations: List<Recommendation>,
    index: LibraryIndex?,
    mode: LibraryMode = LibraryMode.Video,
): List<DashboardTab> {
    if (mode == LibraryMode.Music) {
        return Screens.entries
            .filter {
                it.isMusicTab &&
                    (it != Screens.Playlists || index?.hasPlaylists != false) &&
                    (it != Screens.Podcasts || index?.hasPodcasts != false)
            }
            .map { DashboardTab(route = it(), label = it.tabLabel ?: it(), icon = it.tabIcon) }
    }
    // Before the first index is known, show everything rather than guess.
    fun has(screen: Screens) = when (screen) {
        Screens.Genres, Screens.Movies -> index?.hasMovies != false
        Screens.Shows -> index?.hasShows != false
        // Unlike the others, only once the server says it has channels.
        Screens.Live -> index?.hasGuide == true
        else -> true
    }
    val fixed = Screens.entries.filter { it.isTabItem && has(it) }
        .map { DashboardTab(route = it(), label = it(), icon = it.tabIcon) }
    val recommended = recommendations.mapIndexed { index, r ->
        DashboardTab(route = recommendedRoute(index), label = r.name)
    }
    // Live, when shown, follows Home: for someone used to TV it's the main way in.
    val lead = fixed.takeWhile { it.route == Screens.Home() || it.route == Screens.Live() }
    return lead + recommended + fixed.drop(lead.size)
}

private const val PROFILE_SCREEN_INDEX = -1

/** Widest a tab's label gets, padding included, before it ends in "…". */
private val MaxTabLabelWidth = 240.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DashboardTopBar(
    modifier: Modifier = Modifier,
    selectedTabIndex: Int,
    tabs: List<DashboardTab>,
    /** The profile avatar's, then one per tab. */
    focus: TopBarFocus,
    onAvatarFocusChanged: (isFocused: Boolean) -> Unit = {},
    mode: LibraryMode = LibraryMode.Video,
    /** With music in the library, the logo becomes a video/music switch. */
    canSwitchMode: Boolean = false,
    onSwitchMode: () -> Unit = {},
    onRouteSelection: (route: String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .background(MaterialTheme.colorScheme.surface)
                // Coming up from the page always lands on the current tab (the profile avatar on
                // the profile page), not on whatever is geometrically closest: from a grid's
                // first column that was the avatar. A tab that isn't laid out yet (the row just
                // changed) falls back to the default.
                .focusProperties {
                    enter = { focus.target(selectedTabIndex + 1) ?: FocusRequester.Default }
                }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DisposableEffect(focus) {
                focus.attach(0)
                onDispose { focus.detach(0) }
            }
            UserAvatar(
                modifier = Modifier
                    .size(32.dp)
                    .focusRequester(focus[0])
                    .onFocusChanged { onAvatarFocusChanged(it.hasFocus) }
                    .semantics {
                        contentDescription =
                            StringConstants.Composable.ContentDescription.UserAvatar
                    },
                selected = selectedTabIndex == PROFILE_SCREEN_INDEX,
                onClick = {
                    onRouteSelection(Screens.Profile())
                }
            )
            // The tabs take what's left after the avatar and the logo (a weighted child is
            // measured last), so many or long recommendation tabs can't squeeze the logo.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var isTabRowFocused by remember { mutableStateOf(false) }
                // The tab with focus. The pill follows it at once rather than waiting for the
                // tab's page to load and report back through [selectedTabIndex], which left the
                // newly focused tab dim until navigation finished.
                var focusedTabIndex by remember { mutableStateOf(-1) }
                val highlightedTabIndex =
                    if (isTabRowFocused && focusedTabIndex >= 0) focusedTabIndex else selectedTabIndex

                Spacer(modifier = Modifier.width(20.dp))
                // Where the row and its first and last tabs are, to show ‹ or › where tabs are
                // scrolled out of sight (the TabRow keeps its scroll position to itself).
                var rowLeft by remember { mutableFloatStateOf(0f) }
                var rowRight by remember { mutableFloatStateOf(0f) }
                var firstLeft by remember { mutableFloatStateOf(0f) }
                var lastRight by remember { mutableFloatStateOf(0f) }
                Box(
                    modifier = Modifier.onGloballyPositioned {
                        rowLeft = it.positionInWindow().x
                        rowRight = rowLeft + it.size.width
                    },
                ) {
                    TabRow(
                        modifier = Modifier
                            .onFocusChanged {
                                isTabRowFocused = it.isFocused || it.hasFocus
                            },
                        selectedTabIndex = highlightedTabIndex,
                        indicator = { tabPositions, _ ->
                            if (highlightedTabIndex in tabPositions.indices) {
                                DashboardTopBarItemIndicator(
                                    currentTabPosition = tabPositions[highlightedTabIndex],
                                    anyTabFocused = isTabRowFocused,
                                    shape = JetStreamCardShape
                                )
                            }
                        },
                        separator = { Spacer(modifier = Modifier) }
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            key(index) {
                                DisposableEffect(focus) {
                                    focus.attach(index + 1)
                                    onDispose { focus.detach(index + 1) }
                                }
                                Tab(
                                    modifier = Modifier
                                        .height(32.dp)
                                        .focusRequester(focus[index + 1])
                                        .onGloballyPositioned {
                                            // unclipped, so a tab out of sight still counts
                                            val left = it.positionInWindow().x
                                            if (index == 0) firstLeft = left
                                            if (index == tabs.lastIndex) {
                                                lastRight = left + it.size.width
                                            }
                                        },
                                    selected = index == highlightedTabIndex,
                                    onFocus = {
                                        focusedTabIndex = index
                                        onRouteSelection(tab.route)
                                    },
                                    onClick = { focusManager.moveFocus(FocusDirection.Down) },
                                ) {
                                    if (tab.icon != null) {
                                        Icon(
                                            tab.icon,
                                            modifier = Modifier.padding(4.dp),
                                            contentDescription = StringConstants.Composable
                                                .ContentDescription.DashboardSearchButton,
                                            tint = LocalContentColor.current
                                        )
                                    } else {
                                        Text(
                                            modifier = Modifier
                                                .occupyScreenSize()
                                                .widthIn(max = MaxTabLabelWidth)
                                                .padding(horizontal = 16.dp),
                                            text = tab.label,
                                            // a recommendation set's name comes from the server
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                color = LocalContentColor.current
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (firstLeft < rowLeft - 1f) {
                        HiddenTabs(start = true, modifier = Modifier.align(Alignment.CenterStart))
                    }
                    if (lastRight > rowRight + 1f) {
                        HiddenTabs(start = false, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
            if (canSwitchMode) {
                ModeSwitch(
                    mode = mode,
                    onClick = onSwitchMode,
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else {
                JetStreamLogo(
                    modifier = Modifier
                        .alpha(0.75f)
                        .padding(end = 8.dp),
                )
            }
        }
    }
}

/**
 * An arrow over the end of the tab row where tabs are scrolled out of sight, like the guide's ‹
 * for a program that started earlier, on a fade so it doesn't sit on top of a tab's label.
 */
@Composable
private fun HiddenTabs(start: Boolean, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    // solid under the arrow, fading out over the tab next to it
    val fade = listOf(Color.Transparent, surface, surface)
    Box(
        modifier = modifier
            .width(48.dp)
            .height(32.dp)
            .background(Brush.horizontalGradient(if (start) fade.reversed() else fade)),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Icon(
            if (start) {
                Icons.AutoMirrored.Filled.KeyboardArrowLeft
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The logo as a focusable switch between the video and music tabs: its icon shows the mode
 * (▶ video, ♪ music), then "TAKEOUT ⇄". Compact, since the tab bar leaves little room.
 */
@Composable
private fun ModeSwitch(
    mode: LibraryMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.switch_mode)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(JetStreamCardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
        ),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            JetStreamLogo(
                icon = if (mode == LibraryMode.Music) {
                    Icons.Default.MusicNote
                } else {
                    Icons.Default.PlayCircle
                }
            )
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(IconSize),
            )
        }
    }
}

@Composable
private fun JetStreamLogo(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.PlayCircle,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = StringConstants.Composable
                .ContentDescription.BrandLogoImage,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(IconSize)
        )
        Text(
            text = stringResource(R.string.brand_logo_text),
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            fontFamily = LexendExa
        )
    }
}
