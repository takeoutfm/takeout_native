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

package com.google.jetstream.presentation.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.google.jetstream.R
import com.google.jetstream.data.entities.Recommendation
import com.google.jetstream.data.takeout.LibraryIndex
import com.google.jetstream.data.takeout.LibraryMode
import com.google.jetstream.data.util.StringConstants
import com.google.jetstream.presentation.screens.Screens
import com.google.jetstream.presentation.theme.IconSize
import com.google.jetstream.presentation.theme.JetStreamCardShape
import com.google.jetstream.presentation.theme.LexendExa
import com.google.jetstream.presentation.utils.occupyScreenSize

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
 * Music: Home, Artists.
 */
fun dashboardTabs(
    recommendations: List<Recommendation>,
    index: LibraryIndex?,
    mode: LibraryMode = LibraryMode.Video,
): List<DashboardTab> {
    if (mode == LibraryMode.Music) {
        return Screens.entries.filter { it.isMusicTab }
            .map { DashboardTab(route = it(), label = it.tabLabel ?: it(), icon = it.tabIcon) }
    }
    // Before the first index is known, show everything rather than guess.
    fun has(screen: Screens) = when (screen) {
        Screens.Genres, Screens.Movies -> index?.hasMovies != false
        Screens.Shows -> index?.hasShows != false
        else -> true
    }
    val fixed = Screens.entries.filter { it.isTabItem && has(it) }
        .map { DashboardTab(route = it(), label = it(), icon = it.tabIcon) }
    val recommended = recommendations.mapIndexed { index, r ->
        DashboardTab(route = recommendedRoute(index), label = r.name)
    }
    return listOf(fixed.first()) + recommended + fixed.drop(1)
}

private const val PROFILE_SCREEN_INDEX = -1

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DashboardTopBar(
    modifier: Modifier = Modifier,
    selectedTabIndex: Int,
    tabs: List<DashboardTab>,
    /** One per tab, plus the profile avatar's first. */
    focusRequesters: List<FocusRequester>,
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
                .focusRestorer(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                modifier = Modifier
                    .size(32.dp)
                    .focusRequester(focusRequesters[0])
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
            Row(
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
                            Tab(
                                modifier = Modifier
                                    .height(32.dp)
                                    .focusRequester(focusRequesters[index + 1]),
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
                                            .padding(horizontal = 16.dp),
                                        text = tab.label,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = LocalContentColor.current
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
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
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            fontFamily = LexendExa
        )
    }
}
