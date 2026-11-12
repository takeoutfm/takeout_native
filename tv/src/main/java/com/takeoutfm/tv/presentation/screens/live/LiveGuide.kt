package com.takeoutfm.tv.presentation.screens.live

import com.takeoutfm.shared.guide.Guide
import com.takeoutfm.shared.guide.GuideProgram
import com.takeoutfm.shared.guide.ProgramKind
import com.takeoutfm.shared.guide.TakeoutGuideRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant

/**
 * The guide from now to a few hours on, asked for again when it gets old or runs short, so a
 * screen left open keeps up with the schedule.
 */
class LiveGuide(private val repository: TakeoutGuideRepository) {
    private val lock = Mutex()
    private var guide: Guide? = null
    private var fetchedAt = Instant.DISTANT_PAST

    /** The current guide, fetched first if there's none or it's stale; [force] always fetches. */
    suspend fun get(force: Boolean = false): Guide = lock.withLock {
        val current = guide
        val now = Clock.System.now()
        if (!force && current != null && now - fetchedAt < MaxAge && current.end - current.now() > MinAhead) {
            return current
        }
        repository.guide().also {
            guide = it
            fetchedAt = now
        }
    }

    private companion object {
        val MaxAge = 30.minutes
        val MinAhead = 1.hours
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "9:00 PM" in the TV's time zone. */
fun Instant.localTime(): String = timeFormat.format(toJavaInstant().atZone(ZoneId.systemDefault()))

/** "42 min left", "1 hr 5 min left". */
fun timeLeft(left: Duration): String {
    val minutes = left.inWholeMinutes.coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$minutes min left"
        rest == 0L -> "$hours hr left"
        else -> "$hours hr $rest min left"
    }
}

/** A program's title for lists and banners, "Off Air" when it has left the library. */
val GuideProgram.displayTitle: String
    get() = if (kind == ProgramKind.OffAir) "Off Air" else title
