package com.takeoutfm.android.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.takeoutfm.android.R
import com.takeoutfm.android.ui.video.VideoNav
import com.takeoutfm.shared.guide.Guide
import com.takeoutfm.shared.guide.GuideChannel
import com.takeoutfm.shared.guide.GuideLoader
import com.takeoutfm.shared.guide.GuideProgram
import com.takeoutfm.shared.guide.ProgramKind
import com.takeoutfm.shared.guide.TakeoutGuideRepository
import com.takeoutfm.shared.guide.dayAt
import com.takeoutfm.shared.guide.displayTitle
import com.takeoutfm.shared.guide.floorTo
import com.takeoutfm.shared.guide.guideDays
import com.takeoutfm.shared.guide.musicBlocks
import com.takeoutfm.shared.guide.onNow
import com.takeoutfm.shared.guide.timeLeft
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime

data class LiveState(
    val now: Instant = Clock.System.now(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** The program the guide's detail panel shows, on [channel]. */
data class LiveSelection(val channel: String, val program: GuideProgram)

/**
 * The guide for Home's Live screens, as the TV's: fetched in windows as the grid scrolls into
 * them and merged ([GuideLoader]), started over when it gets old, with the server's time
 * ticking along for the progress bars.
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    repository: TakeoutGuideRepository,
) : ViewModel() {
    private val loader = GuideLoader(repository)

    val guide: StateFlow<Guide?> = loader.guide
    val horizon: StateFlow<Instant?> = loader.horizon

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<LiveSelection?>(null)

    /** What the detail panel shows; null shows the first channel's program on now. */
    val selected: StateFlow<LiveSelection?> = _selected.asStateFlow()

    private var fetchedAt = Instant.DISTANT_PAST

    init {
        viewModelScope.launch {
            while (true) {
                val g = guide.value
                _state.value = _state.value.copy(now = g?.now() ?: Clock.System.now())
                if (g == null || Clock.System.now() - fetchedAt > MaxAge) load()
                delay(TickMs)
            }
        }
    }

    /** Pull to refresh. */
    fun reload() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.value = _state.value.copy(loading = true)
        val ok = loader.reload()
        if (ok) {
            fetchedAt = Clock.System.now()
            val now = guide.value?.now() ?: Clock.System.now()
            // what's next past the first window's end
            loader.ensure(now, now + GuideLoader.Block)
        }
        _state.value = _state.value.copy(
            now = guide.value?.now() ?: Clock.System.now(),
            loading = false,
            failed = !ok && guide.value == null,
        )
    }

    /** Fetches the guide the grid shows, from [from] to [until], and a little past it. */
    fun ensure(from: Instant, until: Instant) {
        viewModelScope.launch { loader.ensure(from, until + GuideLoader.Block) }
    }

    fun select(channel: String, program: GuideProgram) {
        _selected.value = LiveSelection(channel, program)
    }

    private companion object {
        val MaxAge = 30.minutes
        const val TickMs = 30_000L
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "9:00 PM" in this device's time zone. */
fun Instant.localTime(): String = timeFormat.format(toJavaInstant().atZone(ZoneId.systemDefault()))

private fun zone(): TimeZone = TimeZone.currentSystemDefault()

/**
 * Home's Live page: [grid] is the guide grid, else what's on now on each channel. In the list,
 * tapping a channel tunes it; in the grid, tapping a program shows it in the detail panel, with
 * Watch live when it's on and Details for its page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveHome(grid: Boolean, viewModel: LiveViewModel, videoNav: VideoNav) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tune = { channel: String -> LivePlayerActivity.start(context, channel) }
    PullToRefreshBox(
        isRefreshing = state.loading && guide != null,
        onRefresh = viewModel::reload,
        modifier = Modifier.fillMaxSize(),
    ) {
        val g = guide
        when {
            g == null && state.failed -> Message(stringResource(R.string.cant_load))
            g == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            g.channels.isEmpty() -> Message(stringResource(R.string.nothing_here))
            grid -> GuidePage(g, state.now, viewModel, tune) { p -> p.videoId?.let(videoNav.openVideo) }
            else -> OnNowList(g, state.now, tune)
        }
    }
}

@Composable
private fun Message(text: String) {
    // scrollable, so pull to refresh still works
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, modifier = Modifier.padding(32.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun OnNowList(guide: Guide, now: Instant, onTune: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(guide.channels, key = { it.name }) { channel ->
            OnNowRow(channel, now) { onTune(channel.name) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun OnNowRow(channel: GuideChannel, now: Instant, onClick: () -> Unit) {
    val on = onNow(channel, now)
    val program = on.program
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            channel.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (program != null) {
            if (program.block.isNotEmpty() && program.kind != ProgramKind.Reserved) {
                Text(program.block, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                if (program.kind == ProgramKind.Episode && program.subtitle.isNotEmpty()) {
                    "${program.displayTitle} · ${program.subtitle}"
                } else {
                    program.displayTitle
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${program.start.localTime()} – ${program.end.localTime()} · ${timeLeft(program.end - now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { ((now - program.start) / program.duration).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                stringResource(if (channel.breaks.any { now >= it.start && now < it.end }) R.string.live_music else R.string.live_up_next),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val next = on.next
        Text(
            if (next != null) {
                stringResource(R.string.live_next, next.start.localTime(), next.displayTitle)
            } else {
                stringResource(R.string.live_nothing_ahead)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val MinuteWidth = 4.dp
private val NameWidth = 96.dp
private val RowHeight = 64.dp
private val RulerHeight = 28.dp

/** The detail panel, the day row and the grid. */
@Composable
private fun GuidePage(
    guide: Guide,
    now: Instant,
    viewModel: LiveViewModel,
    onTune: (String) -> Unit,
    onDetails: (GuideProgram) -> Unit,
) {
    val horizon by viewModel.horizon.collectAsStateWithLifecycle()
    val chosen by viewModel.selected.collectAsStateWithLifecycle()
    val selection = chosen ?: guide.channels.firstNotNullOfOrNull { c ->
        onNow(c, now).program?.let { LiveSelection(c.name, it) }
    }
    // the grid starts at the half hour it opened in and doesn't go back
    val start = remember { now.floorTo(30.minutes) }
    val end = maxOf(horizon ?: (guide.end + GuideLoader.Block), start + 1.hours)
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pxPerMinute = with(density) { MinuteWidth.toPx() }
    val viewStart = start + (horizontal.value / pxPerMinute).toDouble().minutes
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // a phone on its side: the panel gives the grid the height
    val short = LocalConfiguration.current.screenHeightDp < 480

    Column(Modifier.fillMaxSize()) {
        if (selection != null) {
            Detail(selection, now, wide, short, onTune, onDetails)
            HorizontalDivider()
        }
        val days = guideDays(now, end, zone())
        DayRow(days, viewStart.toLocalDateTime(zone()).date) { day ->
            val at = dayAt(day, now, zone()).floorTo(30.minutes)
            val x = ((at - start).inWholeSeconds / 60f * pxPerMinute).toInt()
            scope.launch { horizontal.animateScrollTo(x) }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val across = ((maxWidth - NameWidth).value / MinuteWidth.value).toDouble().minutes
            // fetch what scrolls into view
            LaunchedEffect(start) {
                snapshotFlow { (horizontal.value / pxPerMinute / 30).toInt() }
                    .distinctUntilChanged()
                    .collect { half ->
                        val from = start + (half * 30).minutes
                        viewModel.ensure(from, from + across)
                    }
            }
            GuideGrid(
                guide = guide,
                now = now,
                start = start,
                end = end,
                // what's drawn: the view and an hour either side
                viewStart = viewStart,
                drawFrom = viewStart - 1.hours,
                drawUntil = viewStart + across + 1.hours,
                horizontal = horizontal,
                selected = selection,
                onTune = onTune,
                onSelect = { c, p -> viewModel.select(c, p) },
            )
        }
    }
}

/** The selected program: art, what it is, when, and Watch live or Details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Detail(
    selection: LiveSelection,
    now: Instant,
    wide: Boolean,
    short: Boolean,
    onTune: (String) -> Unit,
    onDetails: (GuideProgram) -> Unit,
) {
    val p = selection.program
    val on = now >= p.start && now < p.end
    if (short) {
        ShortDetail(selection, now, on, onTune, onDetails)
        return
    }
    val buttons = @Composable { modifier: Modifier, end: Boolean ->
        // wrap to a second line rather than squeeze each other
        FlowRow(
            horizontalArrangement = if (end) Arrangement.spacedBy(8.dp, Alignment.End) else Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = modifier,
        ) {
            if (on && p.videoUri.isNotEmpty()) {
                FilledTonalButton(onClick = { onTune(selection.channel) }) { Text(stringResource(R.string.live_watch)) }
            }
            if (p.videoId != null) {
                OutlinedButton(onClick = { onDetails(p) }) { Text(stringResource(R.string.live_details)) }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val art = p.backdropUri.ifEmpty { p.imageUri }
        if (art.isNotEmpty()) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(if (wide) 240.dp else 120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (p.block.isNotEmpty() && p.kind != ProgramKind.Reserved) {
                Text(p.block, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                p.displayTitle,
                style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                p.kind == ProgramKind.Reserved -> p.subtitle.ifEmpty { stringResource(R.string.live_reserved_note) }
                else -> p.subtitle
            }
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val day = dayLabel(p.start.toLocalDateTime(zone()).date, now)
            Text(
                listOfNotNull(
                    selection.channel,
                    "$day ${p.start.localTime()} – ${p.end.localTime()}",
                    p.rating.ifEmpty { null },
                    if (on) timeLeft(p.end - now) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // wide: beside the art, under the text
            if (wide) buttons(Modifier.padding(top = 4.dp), false)
        }
    }
    // narrow: under the art, at the right, where there's room for both
    if (!wide) buttons(Modifier.fillMaxWidth().padding(top = 8.dp), true)
    }
}

/** The detail panel in two lines: what and when, then the buttons beside the details. */
@Composable
private fun ShortDetail(
    selection: LiveSelection,
    now: Instant,
    on: Boolean,
    onTune: (String) -> Unit,
    onDetails: (GuideProgram) -> Unit,
) {
    val p = selection.program
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(p.block.takeIf { it.isNotEmpty() && p.kind != ProgramKind.Reserved }, p.displayTitle)
                    .joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val day = dayLabel(p.start.toLocalDateTime(zone()).date, now)
            Text(
                listOfNotNull(
                    selection.channel,
                    "$day ${p.start.localTime()} – ${p.end.localTime()}",
                    p.rating.ifEmpty { null },
                    if (on) timeLeft(p.end - now) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (on && p.videoUri.isNotEmpty()) {
            FilledTonalButton(onClick = { onTune(selection.channel) }) { Text(stringResource(R.string.live_watch)) }
        }
        if (p.videoId != null) {
            OutlinedButton(onClick = { onDetails(p) }) { Text(stringResource(R.string.live_details)) }
        }
    }
}

/** "Today", "Tomorrow", "Sat 14". */
@Composable
private fun dayLabel(day: LocalDate, now: Instant): String {
    val today = now.toLocalDateTime(zone()).date
    val d = day.toJavaLocalDate()
    return when ((d.toEpochDay() - today.toJavaLocalDate().toEpochDay()).toInt()) {
        0 -> stringResource(R.string.live_today)
        1 -> stringResource(R.string.live_tomorrow)
        else -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.dayOfMonth}"
    }
}

@Composable
private fun DayRow(days: List<LocalDate>, selected: LocalDate, onDay: (LocalDate) -> Unit) {
    val now = Clock.System.now()
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(days, key = { it.toString() }) { day ->
            FilterChip(
                selected = day == selected,
                onClick = { onDay(day) },
                label = { Text(dayLabel(day, now)) },
            )
        }
    }
}

/** The guide grid: channels down the side, programs across, scrolled together sideways. */
@Composable
private fun GuideGrid(
    guide: Guide,
    now: Instant,
    start: Instant,
    end: Instant,
    viewStart: Instant,
    drawFrom: Instant,
    drawUntil: Instant,
    horizontal: androidx.compose.foundation.ScrollState,
    selected: LiveSelection?,
    onTune: (String) -> Unit,
    onSelect: (String, GuideProgram) -> Unit,
) {
    val vertical = rememberScrollState()
    Row(
        Modifier
            .fillMaxSize()
            .verticalScroll(vertical),
    ) {
        Column(Modifier.width(NameWidth)) {
            Box(Modifier.height(RulerHeight))
            guide.channels.forEach { channel ->
                Box(
                    Modifier
                        .height(RowHeight)
                        .fillMaxWidth()
                        .clickable { onTune(channel.name) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .clipToBounds()
                .horizontalScroll(horizontal),
        ) {
            Box(Modifier.width((end - start).width()).height(RulerHeight + RowHeight * guide.channels.size)) {
                TimeRuler(start, maxOf(drawFrom, start).floorTo(30.minutes), minOf(drawUntil, end))
                guide.channels.forEachIndexed { i, channel ->
                    ChannelRow(
                        channel = channel,
                        start = start,
                        viewStart = viewStart,
                        from = maxOf(drawFrom, start),
                        until = minOf(drawUntil, end),
                        now = now,
                        selected = selected?.takeIf { it.channel == channel.name }?.program,
                        onSelect = { onSelect(channel.name, it) },
                        modifier = Modifier.offset(y = RulerHeight + RowHeight * i),
                    )
                }
                // now
                if (now in start..end) {
                    Box(
                        Modifier
                            .offset(x = (now - start).width())
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(start: Instant, from: Instant, until: Instant) {
    var t = from
    while (t < until) {
        Text(
            if (t.toLocalDateTime(zone()).hour == 0 && t.toLocalDateTime(zone()).minute == 0) {
                dayLabel(t.toLocalDateTime(zone()).date, Clock.System.now())
            } else {
                t.localTime()
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .offset(x = (t - start).width())
                .padding(start = 4.dp, top = 6.dp),
        )
        t += 30.minutes
    }
}

@Composable
private fun ChannelRow(
    channel: GuideChannel,
    start: Instant,
    viewStart: Instant,
    from: Instant,
    until: Instant,
    now: Instant,
    selected: GuideProgram?,
    onSelect: (GuideProgram) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .height(RowHeight)
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        for (m in musicBlocks(channel, 15.minutes)) {
            if (m.end <= from || m.start >= until) continue
            Cell(
                title = stringResource(R.string.live_music),
                detail = m.start.localTime(),
                background = MaterialTheme.colorScheme.surfaceContainerLow,
                dim = true,
                highlighted = false,
                modifier = Modifier
                    .offset(x = (m.start - start).width())
                    .width((m.end - m.start).width() - 2.dp),
                onClick = {},
            )
        }
        for (p in channel.programs) {
            if (p.end <= from || p.start >= until) continue
            // a program that began before the grid's start is cut at it
            val left = maxOf(p.start, start)
            // one that began before the view's left edge keeps its title in view
            val continued = p.start < viewStart
            Cell(
                title = (if (continued) "‹ " else "") + p.displayTitle,
                inset = if (continued) (minOf(viewStart, p.end - 15.minutes) - left).coerceAtLeast(Duration.ZERO).width() else 0.dp,
                detail = when {
                    p.kind == ProgramKind.Reserved -> p.subtitle
                    p.block.isNotEmpty() -> p.block
                    p.kind == ProgramKind.Episode -> p.subtitle
                    else -> p.start.localTime()
                },
                background = when {
                    p.kind == ProgramKind.OffAir -> MaterialTheme.colorScheme.surfaceContainerLow
                    p.block.isNotEmpty() -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                dim = p.kind == ProgramKind.OffAir || p.end <= now,
                highlighted = p == selected,
                modifier = Modifier
                    .offset(x = (left - start).width())
                    .width((p.end - left).width() - 2.dp),
                onClick = { onSelect(p) },
            )
        }
    }
}

@Composable
private fun Cell(
    title: String,
    detail: String,
    inset: Dp = 0.dp,
    background: Color,
    dim: Boolean,
    highlighted: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (highlighted) colors.onPrimary else colors.onSurface.copy(alpha = if (dim) 0.6f else 1f)
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) colors.primary else background)
            .clickable(onClick = onClick)
            .padding(start = 8.dp + inset, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Duration.width(): Dp = MinuteWidth * (inWholeSeconds / 60f)
