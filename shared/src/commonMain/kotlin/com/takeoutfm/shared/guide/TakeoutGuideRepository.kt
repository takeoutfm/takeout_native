package com.takeoutfm.shared.guide

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.model.ApiGuideProgram
import com.takeoutfm.shared.model.ApiGuideView
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

enum class ProgramKind {
    Movie,
    Episode,
    OffAir,

    /** A feature's slot before its programs are chosen: [GuideProgram.title] is its name. */
    Reserved,
}

/** One program on a channel. The gap between its [end] and the next program's start is a break. */
data class GuideProgram(
    val kind: ProgramKind,
    /** The special or feature it's part of, else "". */
    val block: String,
    val start: Instant,
    val end: Instant,
    val title: String,
    /** A movie's tagline or an episode's name. */
    val subtitle: String,
    val rating: String,
    val imageUri: String,
    val backdropUri: String,
    /** The stream; "" off air. */
    val videoUri: String,
    val etag: String,
    /** The movie ("m-<id>") or episode ("e-<id>") for details and Watch Now; null off air. */
    val videoId: String?,
) {
    val duration: Duration get() = end - start
}

/** A channel and its programs, in start order. */
data class GuideChannel(
    /** Unique; a channel's identity. */
    val name: String,
    val programs: List<GuideProgram>,
)

data class Guide(
    /** In the server's lineup order. */
    val channels: List<GuideChannel>,
    val start: Instant,
    val end: Instant,
    /** The server's clock minus this device's when the guide arrived. */
    val skew: Duration,
) {
    /** The server's time now; TV clocks can be wrong, so offsets into programs use this. */
    fun now(clock: Clock = Clock.System): Instant = clock.now() + skew

    fun channel(name: String): GuideChannel? = channels.firstOrNull { it.name == name }
}

/** What a channel is showing at a moment. */
data class OnNow(
    /** Null in a break, or before or after the known programs. */
    val program: GuideProgram?,
    /** How far into [program]; zero without one. */
    val offset: Duration,
    /** The first program starting after the moment. */
    val next: GuideProgram?,
)

/** What [channel] shows at [time], and what comes next. */
fun onNow(channel: GuideChannel, time: Instant): OnNow {
    val program = channel.programs.firstOrNull { time >= it.start && time < it.end }
    val next = channel.programs.firstOrNull { it.start > time }
    return OnNow(program, program?.let { time - it.start } ?: Duration.ZERO, next)
}

/** The channel guide (/api/guide), for the live player and the guide screens. */
class TakeoutGuideRepository(
    private val api: TakeoutApi,
    private val urls: TakeoutUrls,
    private val clock: Clock = Clock.System,
) {
    /** The guide from [start] to [end]; the server defaults to now and 3 hours on. */
    suspend fun guide(start: Instant? = null, end: Instant? = null): Guide {
        val view = api.guide(start, end)
        return toGuide(view, clock.now())
    }

    internal fun toGuide(view: ApiGuideView, received: Instant): Guide {
        val serverNow = if (view.time > 0) Instant.fromEpochMilliseconds(view.time) else received
        return Guide(
            channels = view.channels.orEmpty().map { c ->
                GuideChannel(c.name, c.programs.orEmpty().map { it.toProgram() }.sortedBy { it.start })
            },
            start = view.start ?: serverNow,
            end = view.end ?: serverNow,
            skew = (serverNow - received).inWholeMilliseconds.milliseconds,
        )
    }

    private fun ApiGuideProgram.toProgram(): GuideProgram {
        val kind = when (kind) {
            "movie" -> ProgramKind.Movie
            "episode" -> ProgramKind.Episode
            "reserved" -> ProgramKind.Reserved
            else -> ProgramKind.OffAir
        }
        val id = targetId?.takeIf { it > 0 }
        return GuideProgram(
            kind = kind,
            block = block.orEmpty(),
            start = start,
            end = end,
            title = title,
            subtitle = subtitle.orEmpty(),
            rating = rating.orEmpty(),
            imageUri = urls.absolute(image.orEmpty()),
            backdropUri = urls.absolute(background.orEmpty()),
            videoUri = if (kind == ProgramKind.OffAir || kind == ProgramKind.Reserved) {
                ""
            } else {
                urls.absolute(location.orEmpty())
            },
            etag = etag.orEmpty(),
            videoId = when {
                id == null -> null
                kind == ProgramKind.Movie -> "m-$id"
                kind == ProgramKind.Episode -> "e-$id"
                else -> null
            },
        )
    }
}
