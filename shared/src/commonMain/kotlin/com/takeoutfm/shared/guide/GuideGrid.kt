package com.takeoutfm.shared.guide

import kotlin.time.Duration
import kotlinx.datetime.Instant

/**
 * Two guides as one: channels in first-seen order, each channel's programs from both without
 * repeats (a program spanning the edge between two fetched windows comes back in both).
 */
fun Guide.merge(other: Guide): Guide {
    val names = (channels.map { it.name } + other.channels.map { it.name }).distinct()
    val merged = names.map { name ->
        val programs = (channel(name)?.programs.orEmpty() + other.channel(name)?.programs.orEmpty())
            .distinctBy { it.start }
            .sortedBy { it.start }
        val breaks = (channel(name)?.breaks.orEmpty() + other.channel(name)?.breaks.orEmpty())
            .distinctBy { it.start }
            .sortedBy { it.start }
        GuideChannel(name, programs, breaks)
    }
    return Guide(
        channels = merged,
        start = minOf(start, other.start),
        end = maxOf(end, other.end),
        // the newer fetch has the better idea of the server's clock
        skew = other.skew,
    )
}

/**
 * The program to focus on [channel] for [time], when moving up or down the grid: the one airing
 * then, else the next to start, else the last before it. Breaks have no program, so focus goes
 * to the one after.
 */
fun focusAt(channel: GuideChannel, time: Instant): GuideProgram? =
    channel.programs.firstOrNull { time >= it.start && time < it.end }
        ?: channel.programs.firstOrNull { it.start > time }
        ?: channel.programs.lastOrNull { it.end <= time }

/** The program after [program] on [channel], or null at the end of what's loaded. */
fun nextProgram(channel: GuideChannel, program: GuideProgram): GuideProgram? =
    channel.programs.firstOrNull { it.start > program.start }

/**
 * The program before [program] on [channel], or null. Programs that ended by [notBefore] (now:
 * the guide doesn't go back in time) don't count.
 */
fun previousProgram(channel: GuideChannel, program: GuideProgram, notBefore: Instant): GuideProgram? =
    channel.programs.lastOrNull { it.start < program.start && it.end > notBefore }

/** A run of break music shown in the grid: [start] to [end], without focus or a program. */
data class MusicBlock(val start: Instant, val end: Instant)

/**
 * The channel's music breaks at least [minLength] long, back-to-back tracks as one block. Short
 * breaks between programs stay as plain gaps; a long one (nothing fit before a special) reads
 * as deliberate.
 */
fun musicBlocks(channel: GuideChannel, minLength: Duration): List<MusicBlock> {
    val blocks = mutableListOf<MusicBlock>()
    for (t in channel.breaks.sortedBy { it.start }) {
        val last = blocks.lastOrNull()
        if (last != null && t.start <= last.end) {
            blocks[blocks.size - 1] = last.copy(end = maxOf(last.end, t.end))
        } else {
            blocks += MusicBlock(t.start, t.end)
        }
    }
    return blocks.filter { it.end - it.start >= minLength }
}

/** [time] rounded down to a multiple of [step] since the epoch (half hours line up with clocks). */
fun Instant.floorTo(step: Duration): Instant {
    val ms = step.inWholeMilliseconds
    val t = toEpochMilliseconds()
    return Instant.fromEpochMilliseconds(t - ((t % ms) + ms) % ms)
}
