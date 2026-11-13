package com.takeoutfm.shared.guide

import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * The guide as the Linux (QML, through JSON) and Apple (Swift) apps take it: times in epoch
 * milliseconds, kinds as lower-case strings. One program.
 */
@Serializable
data class LiveProgram(
    /** movie, episode, offair, reserved or track. */
    val kind: String,
    val block: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val subtitle: String,
    val rating: String,
    val imageUri: String,
    val backdropUri: String,
    val videoUri: String,
    /** For details and Watch Now ("m-…", "e-…"), or "". */
    val videoId: String,
)

/** A run of break music long enough to show in the grid. */
@Serializable
data class LiveMusic(val startMs: Long, val endMs: Long)

@Serializable
data class LiveChannel(val name: String, val programs: List<LiveProgram>, val music: List<LiveMusic>)

/**
 * The guide (`guideReload`, `guideEnsure`): what's fetched, from [startMs] to [endMs]. The
 * server's time is the app's clock plus [skewMs]; [horizonMs] is where its schedule ends, 0
 * until known.
 */
@Serializable
data class LiveGuideView(
    val channels: List<LiveChannel>,
    val startMs: Long,
    val endMs: Long,
    val skewMs: Long,
    val horizonMs: Long = 0,
)

/**
 * What the live player plays now on a channel (`tune`): [kind] program (from [offsetMs]), music
 * ([track] from [offsetMs], fading from [fadeFromMs] when that's set), slate (nothing to play)
 * or missing (the channel isn't in the guide). [untilMs] is when to ask again, 0 for unknown.
 */
@Serializable
data class LiveTune(
    val kind: String,
    val channel: String,
    val program: LiveProgram? = null,
    val track: LiveProgram? = null,
    val next: LiveProgram? = null,
    val offsetMs: Long = 0,
    val untilMs: Long = 0,
    val fadeFromMs: Long = 0,
    /** "42 min left" for the banner, or "". */
    val left: String = "",
    val skewMs: Long,
    /** The channels in lineup order, for channel up and down. */
    val lineup: List<String>,
)

fun GuideProgram.toLive() = LiveProgram(
    kind = when (kind) {
        ProgramKind.Movie -> "movie"
        ProgramKind.Episode -> "episode"
        ProgramKind.OffAir -> "offair"
        ProgramKind.Reserved -> "reserved"
        ProgramKind.Track -> "track"
    },
    block = block,
    startMs = start.toEpochMilliseconds(),
    endMs = end.toEpochMilliseconds(),
    title = title,
    subtitle = subtitle,
    rating = rating,
    imageUri = imageUri,
    backdropUri = backdropUri,
    videoUri = videoUri,
    videoId = videoId.orEmpty(),
)

fun Guide.toLive() = LiveGuideView(
    channels = channels.map { c ->
        LiveChannel(
            name = c.name,
            programs = c.programs.map { it.toLive() },
            music = musicBlocks(c, 15.minutes).map { LiveMusic(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds()) },
        )
    },
    startMs = start.toEpochMilliseconds(),
    endMs = end.toEpochMilliseconds(),
    skewMs = skew.inWholeMilliseconds,
)

/** What the loader has; null before anything was fetched. */
fun GuideLoader.page(): LiveGuideView? =
    guide.value?.toLive()?.copy(horizonMs = horizon.value?.toEpochMilliseconds() ?: 0)

fun Guide.tuneLive(name: String): LiveTune {
    val lineup = channels.map { it.name }
    val index = lineup.indexOf(name)
    val skewMs = skew.inWholeMilliseconds
    val channel = channels.getOrNull(index) ?: return LiveTune("missing", name, skewMs = skewMs, lineup = lineup)
    val now = now()
    fun Instant.ms() = toEpochMilliseconds()
    return when (val t = tune(channel, now)) {
        is Tune.Program -> LiveTune(
            "program", name,
            program = t.program.toLive(), next = t.next?.toLive(),
            offsetMs = t.offset.inWholeMilliseconds, untilMs = t.program.end.ms(),
            left = timeLeft(t.program.end - now), skewMs = skewMs, lineup = lineup,
        )
        is Tune.Music -> LiveTune(
            "music", name,
            track = t.track.toLive(), next = t.next?.toLive(),
            offsetMs = t.offset.inWholeMilliseconds, untilMs = t.track.end.ms(),
            fadeFromMs = if (t.cut) (t.track.end - MusicFade).ms() else 0,
            skewMs = skewMs, lineup = lineup,
        )
        is Tune.Slate -> LiveTune(
            "slate", name,
            program = t.program?.toLive(), next = t.next?.toLive(),
            untilMs = t.until?.ms() ?: 0, skewMs = skewMs, lineup = lineup,
        )
    }
}
