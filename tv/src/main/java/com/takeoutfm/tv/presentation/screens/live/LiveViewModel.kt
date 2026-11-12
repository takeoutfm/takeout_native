package com.takeoutfm.tv.presentation.screens.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takeoutfm.shared.guide.Guide
import com.takeoutfm.shared.guide.GuideChannel
import com.takeoutfm.shared.guide.GuideProgram
import com.takeoutfm.shared.guide.TakeoutGuideRepository
import com.takeoutfm.shared.guide.floorTo
import com.takeoutfm.shared.guide.focusAt
import com.takeoutfm.shared.guide.merge
import com.takeoutfm.shared.guide.nextProgram
import com.takeoutfm.shared.guide.previousProgram
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant

sealed interface LiveState {
    data object Loading : LiveState
    data object Error : LiveState
    data object Empty : LiveState
    data object Ready : LiveState
}

/**
 * The guide grid. Focus is a channel plus an anchor time rather than a cell: up and down keep
 * the time and land on whatever overlaps it, so focus doesn't wander as cell widths change.
 * The guide is fetched in [Block]-long windows as the grid scrolls into them.
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repository: TakeoutGuideRepository,
) : ViewModel() {
    var state by mutableStateOf<LiveState>(LiveState.Loading)
        private set
    var guide by mutableStateOf<Guide?>(null)
        private set

    /** The server's time, moved on by [tick]. */
    var now by mutableStateOf(Clock.System.now())
        private set

    /** The focused channel's index in [Guide.channels]. */
    var channelIndex by mutableStateOf(0)
        private set
    private var anchor by mutableStateOf(now)

    /** The time at the grid's left edge, on a half hour. */
    var viewStart by mutableStateOf(now.floorTo(Step))
        private set

    /** How much time the grid shows across, set from its width. */
    var visible: Duration = 2.hours
        set(value) {
            field = value
            ensureLoaded()
        }

    /** Whether the grid had focus, so coming back to the tab focuses it again. */
    var gridHadFocus = false

    private val loaded = mutableSetOf<Instant>()
    private val loading = mutableSetOf<Instant>()

    /** No guide after this: the server stops at its schedule horizon. */
    private var horizon by mutableStateOf<Instant?>(null)

    init {
        load()
    }

    fun load() {
        state = LiveState.Loading
        loaded.clear()
        horizon = null
        guide = null
        tick()
        anchor = now
        viewStart = now.floorTo(Step)
        viewModelScope.launch {
            state = if (fetch(now.floorTo(Block))) LiveState.Ready else LiveState.Error
            if (guide?.channels.isNullOrEmpty()) state = LiveState.Empty
            ensureLoaded()
            findHorizon()
        }
    }

    /**
     * Asks for a moment far past any horizon: the server cuts the end back to its horizon, which
     * says how many days the day row can offer before the grid has scrolled that far.
     */
    private suspend fun findHorizon() {
        val far = now + 60.days
        try {
            val end = repository.guide(far, far + 1.minutes).end
            if (end < far) horizon = end
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the day row grows as the grid loads instead
        }
    }

    fun tick() {
        now = guide?.now() ?: Clock.System.now()
        val earliest = now.floorTo(Step)
        if (viewStart < earliest) viewStart = earliest
    }

    val channel: GuideChannel? get() = guide?.channels?.getOrNull(channelIndex)

    /** The focused program, or null on an empty channel. */
    val program: GuideProgram?
        get() = channel?.let { focusAt(it, maxOf(anchor, now)) }

    /** Up and down: another channel at the same time. False at the top, so focus can leave. */
    fun moveChannel(delta: Int): Boolean {
        val count = guide?.channels?.size ?: return false
        val to = channelIndex + delta
        if (to !in 0 until count) return false
        channelIndex = to
        reveal()
        return true
    }

    /** Left and right: the previous or next program on this channel. */
    fun moveProgram(delta: Int): Boolean {
        val c = channel ?: return false
        val p = program ?: return false
        val to = if (delta > 0) nextProgram(c, p) else previousProgram(c, p, now)
        if (to == null) {
            // Nothing loaded yet past the edge; it's on its way.
            ensureLoaded(p.end + Block)
            return true
        }
        anchor = maxOf(to.start, now)
        reveal()
        return true
    }

    /** The days the guide reaches, from today, for the day row. */
    fun days(zone: ZoneId = ZoneId.systemDefault()): List<LocalDate> {
        val today = now.toJavaInstant().atZone(zone).toLocalDate()
        val last = (horizon ?: guide?.end ?: now).toJavaInstant().atZone(zone).toLocalDate()
        return generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.take(14).toList()
    }

    /** The day of the focused program. */
    fun day(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        maxOf(anchor, now).toJavaInstant().atZone(zone).toLocalDate()

    /** Jumps to [day] at this time of day (today: now). */
    fun goToDay(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()) {
        val time = now.toJavaInstant().atZone(zone)
        val at = time.with(day).toInstant().toKotlinInstant()
        anchor = maxOf(at, now)
        viewStart = maxOf(anchor.floorTo(Step), now.floorTo(Step))
        ensureLoaded()
    }

    /** Scrolls so the focused program's start (or now, if it's on) is in view. */
    private fun reveal() {
        val p = program ?: return
        val from = maxOf(p.start, now)
        val earliest = now.floorTo(Step)
        if (from < viewStart) {
            viewStart = maxOf(from.floorTo(Step), earliest)
        } else if (from >= viewStart + visible - Step) {
            viewStart = maxOf(from.floorTo(Step) - Step, earliest)
        }
        ensureLoaded()
    }

    /** Fetches the windows the grid shows, and the next one, if they aren't loaded. */
    private fun ensureLoaded(until: Instant = viewStart + visible + Block) {
        if (state != LiveState.Ready) return
        var block = viewStart.floorTo(Block)
        while (block < until) {
            val limit = horizon
            if (limit != null && block >= limit) break
            if (block !in loaded && block !in loading) {
                val b = block
                viewModelScope.launch { fetch(b) }
            }
            block += Block
        }
    }

    private suspend fun fetch(block: Instant): Boolean {
        loading += block
        try {
            val fresh = repository.guide(block, block + Block)
            // The server cuts the end off at its horizon.
            if (fresh.end < block + Block) horizon = fresh.end
            guide = guide?.merge(fresh) ?: fresh
            loaded += block
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            loading -= block
        }
    }

    companion object {
        /** The grid's columns, and where it can start. */
        val Step = 30.minutes

        /** How much guide each fetch asks for. */
        val Block = 6.hours
    }
}
