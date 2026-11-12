package com.takeoutfm.shared.guide

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The guide from now to a few hours on, asked for again when it gets old or runs short, so a
 * screen left open keeps up with the schedule.
 */
class LiveGuide(
    private val repository: TakeoutGuideRepository,
    private val clock: Clock = Clock.System,
) {
    private val lock = Mutex()
    private var guide: Guide? = null
    private var fetchedAt = Instant.DISTANT_PAST

    /** The current guide, fetched first if there's none or it's stale; [force] always fetches. */
    suspend fun get(force: Boolean = false): Guide = lock.withLock {
        val current = guide
        val now = clock.now()
        if (!force && current != null && now - fetchedAt < MaxAge && current.end - current.now(clock) > MinAhead) {
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

/** What a live player should do on a channel at a moment. */
sealed interface Tune {
    /** What's on the screen until [until]: the next program's start, or a slot's end. */
    val next: GuideProgram?

    /** Play [program] from [offset] until its slot ends at [GuideProgram.end]. */
    data class Program(val program: GuideProgram, val offset: Duration, override val next: GuideProgram?) : Tune

    /**
     * Break music: [track] from [offset] until its [GuideProgram.end], under the up-next slate.
     * [cut] when the next program cuts it off, so it fades out.
     */
    data class Music(
        val track: GuideProgram,
        val offset: Duration,
        override val next: GuideProgram?,
        val cut: Boolean,
    ) : Tune

    /**
     * A silent break, before the first program, or [program] that's off air: the up-next slate
     * until [until] (null: nothing known ahead).
     */
    data class Slate(val program: GuideProgram?, override val next: GuideProgram?, val until: Instant?) : Tune
}

/** What to play on [channel] at [time]: the program on, else its break music, else the slate. */
fun tune(channel: GuideChannel, time: Instant): Tune {
    val on = onNow(channel, time)
    val program = on.program
    if (program != null && program.kind != ProgramKind.OffAir && program.kind != ProgramKind.Reserved &&
        program.videoUri.isNotEmpty()
    ) {
        return Tune.Program(program, on.offset, on.next)
    }
    if (program == null) {
        val music = breakAt(channel, time)
        if (music != null && music.first.videoUri.isNotEmpty()) {
            val (track, offset) = music
            return Tune.Music(track, offset, on.next, cut = on.next?.start == track.end)
        }
    }
    return Tune.Slate(program, on.next, program?.end ?: on.next?.start)
}

/** Break music's volume at [time]: full, then down to nothing over the last [fade] when cut. */
fun Tune.Music.volume(time: Instant, fade: Duration = MusicFade): Float {
    if (!cut) return 1f
    val left = track.end - time
    return (left / fade).toFloat().coerceIn(0f, 1f)
}

/** How long break music fades out before the program that cuts it off. */
val MusicFade = 3.seconds

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
