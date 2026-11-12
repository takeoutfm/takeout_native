package com.takeoutfm.tv.presentation.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.takeoutfm.shared.guide.GuideChannel
import com.takeoutfm.shared.guide.GuideProgram
import com.takeoutfm.shared.guide.MusicBlock
import com.takeoutfm.shared.guide.ProgramKind
import com.takeoutfm.shared.guide.musicBlocks
import com.takeoutfm.tv.presentation.common.Error
import com.takeoutfm.tv.presentation.common.MusicHomeSkeleton
import com.takeoutfm.tv.presentation.screens.music.common.MusicImage
import com.takeoutfm.tv.presentation.screens.music.common.MusicMessage
import com.takeoutfm.tv.presentation.theme.JetStreamButtonShape
import com.takeoutfm.tv.presentation.theme.JetStreamCardShape
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant

/** Width of a minute in the grid: a half hour is 180dp. */
private val MinuteWidth = 6.dp
private val ChannelColumn = 160.dp
private val RowHeight = 64.dp

/** For a feature slot whose programs aren't chosen yet. */
private const val ReservedNote = "Programs announced the day before"

/** Breaks this long show as music in the grid; shorter ones are plain gaps. */
private val MusicBlockMin = 15.minutes

/**
 * The Live tab: the channel guide. Select tunes to a program that's on now and opens the details
 * (Watch Now, Start Over) of one that isn't; holding select opens the details of either.
 */
@Composable
fun LiveScreen(
    onChannelClick: (channel: String) -> Unit,
    onProgramClick: (videoId: String) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    // On each minute, so the clock and the now line turn over with it.
    LaunchedEffect(Unit) {
        while (true) {
            val ms = viewModel.now.toEpochMilliseconds()
            delay(60_000 - ms % 60_000 + 50)
            viewModel.tick()
        }
    }
    when (viewModel.state) {
        LiveState.Loading -> MusicHomeSkeleton(Modifier.fillMaxSize())
        LiveState.Error -> Error(Modifier.fillMaxSize(), onRetry = viewModel::load)
        LiveState.Empty -> MusicMessage("Nothing is on right now.")
        LiveState.Ready -> GuideGrid(viewModel, onChannelClick, onProgramClick, onScroll)
    }
}

@Composable
private fun GuideGrid(
    viewModel: LiveViewModel,
    onChannelClick: (channel: String) -> Unit,
    onProgramClick: (videoId: String) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
) {
    val guide = viewModel.guide ?: return
    val now = viewModel.now
    val focused = viewModel.program
    var gridFocused by remember { mutableStateOf(false) }
    val gridFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // The top bar makes room for more channels once focus is past the first one.
    LaunchedEffect(gridFocused, viewModel.channelIndex) {
        onScroll(!gridFocused || viewModel.channelIndex == 0)
    }
    // Keep the focused channel on screen, with one above it when there is one.
    LaunchedEffect(viewModel.channelIndex) {
        val i = viewModel.channelIndex
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (i <= first) listState.animateScrollToItem(maxOf(i - 1, 0))
        else if (i >= last) listState.animateScrollToItem(maxOf(i - (last - first) + 1, 0))
    }
    // Back from the player or a title: focus the grid again, not the day row.
    LaunchedEffect(Unit) {
        if (viewModel.gridHadFocus) {
            repeat(30) {
                if (runCatching { gridFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(16)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp)
    ) {
        ProgramHeader(viewModel.channel, focused, now)
        DayRow(viewModel.days(), viewModel.day(), viewModel::goToDay)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val across = maxWidth - ChannelColumn
            LaunchedEffect(across) { viewModel.visible = (across / MinuteWidth).toDouble().minutes }
            val viewStart = viewModel.viewStart
            val viewEnd = viewStart + viewModel.visible
            Column {
                TimeRuler(viewStart, viewEnd)
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(gridFocus)
                        .onFocusChanged {
                            gridFocused = it.isFocused
                            if (it.isFocused) viewModel.gridHadFocus = true
                        }
                        .guideKeys(viewModel, onChannelClick, onProgramClick)
                        .focusable()
                ) {
                    LazyColumn(state = listState, userScrollEnabled = false) {
                        itemsIndexed(guide.channels, key = { _, c -> c.name }) { i, channel ->
                            ChannelRow(
                                channel = channel,
                                focused = if (i == viewModel.channelIndex) focused else null,
                                gridFocused = gridFocused,
                                viewStart = viewStart,
                                viewEnd = viewEnd,
                            )
                        }
                    }
                    NowLine(now, viewStart, viewEnd)
                }
            }
        }
    }
}

/** The D-pad, handled here rather than by focus search, since cells vary in width. */
private fun Modifier.guideKeys(
    viewModel: LiveViewModel,
    onChannelClick: (String) -> Unit,
    onProgramClick: (String) -> Unit,
): Modifier {
    var held = false
    return onKeyEvent { event ->
        val center = event.key == Key.DirectionCenter || event.key == Key.Enter ||
            event.key == Key.NumPadEnter
        if (center) {
            val program = viewModel.program
            val channel = viewModel.channel
            when (event.type) {
                KeyEventType.KeyDown -> {
                    // Held: details, even for what's on now (Start Over).
                    if (event.nativeKeyEvent.repeatCount == 1 && !held) {
                        held = true
                        program?.videoId?.let(onProgramClick)
                    }
                }

                KeyEventType.KeyUp -> {
                    if (!held && program != null && channel != null) {
                        if (program.isOn(viewModel.now)) onChannelClick(channel.name)
                        else program.videoId?.let(onProgramClick)
                    }
                    held = false
                }
            }
            return@onKeyEvent true
        }
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.DirectionUp -> viewModel.moveChannel(-1)
            Key.DirectionDown -> viewModel.moveChannel(1)
            Key.DirectionLeft -> viewModel.moveProgram(-1)
            Key.DirectionRight -> viewModel.moveProgram(1)
            else -> when (event.key.nativeKeyCode) {
                android.view.KeyEvent.KEYCODE_CHANNEL_UP -> viewModel.moveChannel(-1) || true
                android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> viewModel.moveChannel(1) || true
                else -> false
            }
        }
    }
}

private fun GuideProgram.isOn(now: Instant) = now >= start && now < end

@Composable
private fun ProgramHeader(channel: GuideChannel?, program: GuideProgram?, now: Instant) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .padding(vertical = 8.dp),
    ) {
        val image = program?.backdropUri?.ifEmpty { null } ?: program?.imageUri.orEmpty()
        MusicImage(
            url = image,
            contentDescription = null,
            modifier = Modifier
                .size(width = 238.dp, height = 134.dp)
                .clip(JetStreamCardShape),
            placeholderKey = program?.title ?: channel?.name.orEmpty(),
            // a feature slot has no art yet
            fallbackText = program?.displayTitle,
        )
        Column(
            Modifier
                .padding(start = 24.dp)
                .weight(1f)
        ) {
            // a reserved slot's title is already the feature's name
            val block = program?.block.orEmpty().takeIf { program?.kind != ProgramKind.Reserved }.orEmpty()
            if (block.isNotEmpty()) {
                Text(
                    text = block,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = program?.displayTitle ?: channel?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = program?.subtitle.orEmpty()
                .ifEmpty { if (program?.kind == ProgramKind.Reserved) ReservedNote else "" }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (program != null) {
                val parts = listOfNotNull(
                    channel?.name,
                    "${program.start.dayLabel(now)}${program.start.localTime()} – ${program.end.localTime()}",
                    program.rating.ifEmpty { null },
                    if (program.isOn(now)) timeLeft(program.end - now) else null,
                )
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Text(
            text = now.localTime(),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .align(Alignment.Top)
                .padding(start = 24.dp),
        )
    }
}

/** "" today, "Tomorrow ", "Fri ". */
private fun Instant.dayLabel(now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = toJavaInstant().atZone(zone).toLocalDate()
    val today = now.toJavaInstant().atZone(zone).toLocalDate()
    return when (day) {
        today -> ""
        today.plusDays(1) -> "Tomorrow "
        else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " "
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DayRow(days: List<LocalDate>, selected: LocalDate, onDay: (LocalDate) -> Unit) {
    if (days.size < 2) return
    // Coming into the row, focus the day the grid is showing, not the nearest chip.
    val selectedFocus = remember { FocusRequester() }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(bottom = 8.dp)
            .focusProperties { enter = { selectedFocus } },
    ) {
        items(days, key = { it.toEpochDay() }) { day ->
            val label = when (day) {
                days.first() -> "Today"
                days.first().plusDays(1) -> "Tomorrow"
                else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                    " " + day.dayOfMonth
            }
            Surface(
                onClick = { onDay(day) },
                modifier = if (day == selected) Modifier.focusRequester(selectedFocus) else Modifier,
                shape = ClickableSurfaceDefaults.shape(JetStreamButtonShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (day == selected) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        Color.Transparent
                    },
                    focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                ),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TimeRuler(viewStart: Instant, viewEnd: Instant) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(start = ChannelColumn)
            .clipToBounds()
    ) {
        var t = viewStart
        while (t < viewEnd) {
            Text(
                text = t.localTime(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.offset(x = (t - viewStart).width() + 6.dp),
            )
            t += LiveViewModel.Step
        }
    }
}

@Composable
private fun ChannelRow(
    channel: GuideChannel,
    focused: GuideProgram?,
    gridFocused: Boolean,
    viewStart: Instant,
    viewEnd: Instant,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .padding(vertical = 2.dp)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .padding(end = 8.dp),
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            for (m in musicBlocks(channel, MusicBlockMin)) {
                if (m.end <= viewStart || m.start >= viewEnd) continue
                val from = maxOf(m.start, viewStart)
                val to = minOf(m.end, viewEnd)
                MusicCell(
                    block = m,
                    modifier = Modifier
                        .offset(x = (from - viewStart).width())
                        .width((to - from).width() - 3.dp)
                        .fillMaxHeight(),
                )
            }
            for (p in channel.programs) {
                if (p.end <= viewStart || p.start >= viewEnd) continue
                val from = maxOf(p.start, viewStart)
                val to = minOf(p.end, viewEnd)
                ProgramCell(
                    program = p,
                    isFocused = p == focused,
                    gridFocused = gridFocused,
                    // a program that began before the left edge shows it's continuing
                    continued = p.start < viewStart,
                    modifier = Modifier
                        .offset(x = (from - viewStart).width())
                        .width((to - from).width() - 3.dp)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun ProgramCell(
    program: GuideProgram,
    isFocused: Boolean,
    gridFocused: Boolean,
    continued: Boolean,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val highlighted = isFocused && gridFocused
    val shape = RoundedCornerShape(6.dp)
    val special = program.block.isNotEmpty()
    val background = when {
        highlighted -> colors.inverseSurface
        program.kind == ProgramKind.OffAir -> colors.surfaceVariant.copy(alpha = 0.3f)
        // a special's programs read as one event
        special -> colors.primary.copy(alpha = 0.28f)
        else -> colors.surfaceVariant.copy(alpha = 0.6f)
    }
    val content = if (highlighted) colors.inverseOnSurface else colors.onSurface
    Box(
        modifier
            .clip(shape)
            .background(background)
            .then(
                // Focus is elsewhere (the day row): still show which program the grid is on.
                if (isFocused && !gridFocused) {
                    Modifier.border(2.dp, colors.onSurface.copy(alpha = 0.5f), shape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                text = (if (continued) "‹ " else "") + program.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = when {
                program.kind == ProgramKind.Reserved -> program.subtitle.ifEmpty { ReservedNote }
                program.block.isNotEmpty() -> program.block
                program.kind == ProgramKind.Episode -> program.subtitle
                else -> program.start.localTime()
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A long music break: shown so the gap reads as deliberate, but not focusable. */
@Composable
private fun MusicCell(block: MusicBlock, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                text = "Music",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = block.start.localTime(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NowLine(now: Instant, viewStart: Instant, viewEnd: Instant) {
    if (now < viewStart || now >= viewEnd) return
    Box(
        Modifier
            .padding(start = ChannelColumn)
            .offset(x = (now - viewStart).width())
            .width(2.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.primary)
    )
}

private fun Duration.width(): Dp = MinuteWidth * (inWholeSeconds / 60f)
