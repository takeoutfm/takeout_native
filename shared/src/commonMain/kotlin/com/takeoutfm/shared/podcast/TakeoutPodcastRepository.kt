package com.takeoutfm.shared.podcast

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.model.ApiPodcastEpisode
import com.takeoutfm.shared.model.ApiPodcastSeries
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.util.EditedState
import com.takeoutfm.shared.util.sortedByTitle
import com.takeoutfm.shared.video.ProgressSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A podcast ("ps-<id>"). */
@Serializable
data class PodcastSeries(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val imageUri: String,
)

/** Where a podcast episode stands for this user. */
@Serializable
sealed interface EpisodeProgress {
    @Serializable @SerialName("NotStarted")
    data object NotStarted : EpisodeProgress
    /** [fraction] 0–1 and minutes left. */
    @Serializable @SerialName("Started")
    data class Started(val fraction: Float, val minutesLeft: Int) : EpisodeProgress
    @Serializable @SerialName("Complete")
    data object Complete : EpisodeProgress
}

/** A podcast episode ("pe-<id>"); [eid] (its GUID) keys progress. */
@Serializable
data class PodcastEpisode(
    val id: String,
    val eid: String,
    val seriesTitle: String,
    val title: String,
    val author: String,
    val description: String,
    /** ISO date it was published. */
    val date: String,
    val imageUri: String,
    val progress: EpisodeProgress = EpisodeProgress.NotStarted,
)

@Serializable
data class PodcastDetails(
    val series: PodcastSeries,
    val subscribed: Boolean,
    val episodes: List<PodcastEpisode>,
)

/**
 * These details as the core now has them (decision 017): each episode's progress from
 * [offsets] (`TakeoutProgress.offsets`) and Subscribed from [subscribed]
 * ([TakeoutPodcastRepository.subscribedSeries]; null, not yet loaded, keeps the loaded value).
 * Pages combine their loaded details with those flows, so playback, Mark as Played and
 * Subscribe show without loading the page again.
 */
fun PodcastDetails.following(offsets: Map<String, ApiOffset>, subscribed: List<PodcastSeries>?): PodcastDetails =
    copy(
        subscribed = subscribed?.any { it.id == series.id } ?: this.subscribed,
        episodes = episodes.map { e ->
            val progress = TakeoutPodcastRepository.episodeProgress(offsets[e.eid])
            if (progress == e.progress) e else e.copy(progress = progress)
        },
    )

/**
 * The server's podcasts for every app: all and subscribed series, a series' episodes with
 * this user's progress ("N min left", Complete), subscribing, and play queues of episodes that
 * resume where they were left.
 */
class TakeoutPodcastRepository(
    private val api: TakeoutApi,
    private val urls: TakeoutUrls,
    private val progress: ProgressSource,
) {
    private val subscribed = EditedState<List<PodcastSeries>?>(null)

    /**
     * The subscribed podcasts A–Z as last loaded ([series], [details]), kept current by
     * [setSubscribed]: pages observe this rather than reloading after a change (decision 017).
     * Null until first loaded.
     */
    val subscribedSeries: StateFlow<List<PodcastSeries>?> = subscribed.flow

    /** Every podcast loaded so far, by id: what [setSubscribed] adds to [subscribedSeries]. */
    private val seen = MutableStateFlow<Map<String, PodcastSeries>>(emptyMap())

    /** Every podcast on the server (A–Z), or only the subscribed ones. */
    suspend fun series(subscribedOnly: Boolean): List<PodcastSeries> {
        if (!subscribedOnly) {
            return api.podcasts().series.orEmpty().map { it.toSeries() }.sortedSeries().also { remember(it) }
        }
        // the subscriptions as the server has them, unless a change overlapped this load
        return subscribed.load(
            fetch = { api.subscribedPodcasts().series.orEmpty().map { it.toSeries() }.sortedSeries().also { remember(it) } },
            toState = { it },
        )
    }

    private fun List<PodcastSeries>.sortedSeries() = sortedByTitle { it.title }

    private fun remember(series: List<PodcastSeries>) = seen.update { it + series.associateBy { s -> s.id } }

    suspend fun details(seriesId: String): PodcastDetails {
        val id = numericId(seriesId, SeriesPrefix)
        val view = api.podcastSeries(id)
        val subscribedList = orNull { series(subscribedOnly = true) }
        val offsets = orNull { progress.latest() }.orEmpty()
        val title = view.series.title
        val series = view.series.toSeries()
        remember(listOf(series))
        return PodcastDetails(
            series = series,
            subscribed = subscribedList?.any { it.id == seriesId } ?: false,
            episodes = view.episodes.orEmpty().map { it.toEpisode(title, offsets[it.eid]) },
        )
    }

    /** The saved position of the episode with GUID [eid], or null. */
    suspend fun offset(eid: String): ApiOffset? = orNull { progress.latest()[eid] }

    /** Updates [subscribedSeries] right away; reverted if the server refuses. */
    suspend fun setSubscribed(seriesId: String, subscribe: Boolean) = subscribed.edit {
        val before = subscribed.value
        val series = seen.value[seriesId]
        subscribed.update { list ->
            when {
                list == null -> null
                !subscribe -> list.filter { it.id != seriesId }
                series == null || list.any { it.id == seriesId } -> list
                else -> (list + series).sortedSeries()
            }
        }
        try {
            api.setSubscribed(numericId(seriesId, SeriesPrefix), subscribe)
        } catch (e: Exception) {
            subscribed.set(before)
            throw e
        }
        // a podcast never loaded here: only the server has it
        if (subscribe && series == null && before != null) orNull { series(subscribedOnly = true) }
        Unit
    }

    /**
     * [episodes] from [startIndex] as a queue (newest first, as listed), starting where the
     * first one was left unless it's finished.
     */
    suspend fun episodesQueue(title: String, imageUri: String, episodes: List<PodcastEpisode>, startIndex: Int): MusicQueue {
        val start = episodes.getOrNull(startIndex)
        val resumeMs = start?.let { orNull { progress.latest()[it.eid] } }
            ?.takeIf { ProgressRules.shouldResume(it.offset, it.duration, ProgressRules.PodcastEndPercent) }
            ?.let { it.offset * 1000L } ?: 0L
        return MusicQueue(
            title = title,
            creator = start?.author.orEmpty(),
            imageUri = imageUri,
            items = episodes.map { e ->
                QueueItem(
                    title = e.title,
                    artist = e.author.ifEmpty { e.seriesTitle },
                    album = e.seriesTitle,
                    imageUri = e.imageUri.ifEmpty { imageUri },
                    uri = urls.absolute("/api/episodes/${numericId(e.id, EpisodePrefix)}/location"),
                    etag = e.eid,
                    podcast = true,
                )
            },
            startIndex = startIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)),
            startPositionMs = resumeMs,
        )
    }

    private fun ApiPodcastSeries.toSeries() = PodcastSeries(
        id = "$SeriesPrefix$id",
        title = title,
        author = author,
        description = description,
        imageUri = urls.absolute(image),
    )

    private fun ApiPodcastEpisode.toEpisode(seriesTitle: String, offset: ApiOffset?) = PodcastEpisode(
        id = "$EpisodePrefix$id",
        eid = eid,
        seriesTitle = seriesTitle,
        title = title,
        author = author,
        description = description,
        date = date,
        imageUri = urls.absolute(image),
        progress = episodeProgress(offset),
    )

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val SeriesPrefix = "ps-"
        const val EpisodePrefix = "pe-"

        fun numericId(id: String, prefix: String): Int =
            id.removePrefix(prefix).toIntOrNull() ?: throw IllegalArgumentException("Not a $prefix id: $id")

        /**
         * The position and length (seconds) to save to mark an episode played, or not played:
         * its known length if it has been played before; otherwise a token length, since the
         * real one isn't known until it plays (played → Complete, not played → Not started).
         */
        fun playedMark(known: ApiOffset?, played: Boolean): Pair<Int, Int> {
            val duration = known?.duration?.takeIf { it > 0 } ?: ProgressRules.MinResumeSeconds
            return (if (played) duration else 0) to duration
        }

        /** Not started (or barely), started with time left, or within the end (Complete). */
        fun episodeProgress(offset: ApiOffset?): EpisodeProgress = when {
            offset == null || offset.offset < ProgressRules.MinResumeSeconds -> EpisodeProgress.NotStarted
            ProgressRules.isFinished(offset, ProgressRules.PodcastEndPercent) -> EpisodeProgress.Complete
            offset.duration > 0 -> EpisodeProgress.Started(
                fraction = offset.offset.toFloat() / offset.duration,
                minutesLeft = ((offset.duration - offset.offset) / 60).coerceAtLeast(1),
            )
            else -> EpisodeProgress.Started(fraction = 0f, minutesLeft = 0)
        }
    }
}
