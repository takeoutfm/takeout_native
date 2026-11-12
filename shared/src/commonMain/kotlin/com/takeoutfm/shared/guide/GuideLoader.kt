package com.takeoutfm.shared.guide

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * The guide for browsing days ahead, as the TV's grid does: fetched in [Block]-long windows as
 * they come into view and merged into one [guide], and the server's schedule horizon found up
 * front so a day row knows how many days it has.
 */
class GuideLoader(
    private val repository: TakeoutGuideRepository,
    private val clock: Clock = Clock.System,
) {
    private val _guide = MutableStateFlow<Guide?>(null)

    /** Everything fetched so far, merged; null until the first window arrives. */
    val guide: StateFlow<Guide?> = _guide.asStateFlow()

    private val _horizon = MutableStateFlow<Instant?>(null)

    /** No guide after this: where the server's schedule ends, once known. */
    val horizon: StateFlow<Instant?> = _horizon.asStateFlow()

    private val lock = Mutex()
    private val loaded = mutableSetOf<Instant>()
    private val loading = mutableSetOf<Instant>()

    /**
     * Starts over from now: fetches the window with now in it in place of everything fetched,
     * and finds the horizon. False when that window can't be fetched (the old guide stays).
     */
    suspend fun reload(): Boolean {
        lock.withLock {
            loaded.clear()
            loading.clear()
        }
        // what was shown stays until the fresh guide replaces it
        val now = clock.now()
        val ok = fetch(now.floorTo(Block), replace = true)
        if (ok) findHorizon(now)
        return ok
    }

    /** Fetches the windows from [from] to [until] that aren't fetched yet, at once. */
    suspend fun ensure(from: Instant, until: Instant) = coroutineScope {
        var block = from.floorTo(Block)
        while (block < until) {
            val limit = _horizon.value
            if (limit != null && block >= limit) break
            val b = block
            val start = lock.withLock {
                if (b in loaded || b in loading) false else { loading += b; true }
            }
            if (start) launch { fetch(b, claimed = true) }
            block += Block
        }
    }

    private suspend fun fetch(block: Instant, claimed: Boolean = false, replace: Boolean = false): Boolean {
        if (!claimed) lock.withLock { loading += block }
        try {
            val fresh = repository.guide(block, block + Block)
            // The server cuts the end off at its horizon.
            if (fresh.end < block + Block) _horizon.value = fresh.end
            lock.withLock {
                _guide.value = if (replace) fresh else _guide.value?.merge(fresh) ?: fresh
                loaded += block
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            lock.withLock { loading -= block }
        }
    }

    /**
     * Asks for a moment far past any horizon: the server cuts the end back to its horizon, which
     * says how many days there are before the grid has scrolled that far.
     */
    private suspend fun findHorizon(now: Instant) {
        val far = now + 60.days
        try {
            val end = repository.guide(far, far + 1.minutes).end
            if (end < far) _horizon.value = end
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the days grow as the guide loads instead
        }
    }

    companion object {
        /** How much guide each fetch asks for. */
        val Block: Duration = 6.hours
    }
}

/** The days from [now]'s through [last]'s in [zone], at most [max]: a guide's day row. */
fun guideDays(now: Instant, last: Instant, zone: TimeZone, max: Int = 14): List<LocalDate> {
    val today = now.toLocalDateTime(zone).date
    val end = maxOf(last, now).toLocalDateTime(zone).date
    return generateSequence(today) { it.plus(1, DateTimeUnit.DAY) }
        .takeWhile { it <= end }
        .take(max)
        .toList()
}

/** [day] at [now]'s time of day in [zone] (today: now itself), where a day row jumps to. */
fun dayAt(day: LocalDate, now: Instant, zone: TimeZone): Instant {
    val today = now.toLocalDateTime(zone).date
    if (day <= today) return now
    return day.atStartOfDayIn(zone) + (now - today.atStartOfDayIn(zone))
}
