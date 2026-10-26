package com.takeoutfm.shared.podcast

import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.api.TakeoutUrls
import com.takeoutfm.shared.model.ApiOffset
import com.takeoutfm.shared.model.ApiPodcastEpisode
import com.takeoutfm.shared.model.ApiPodcastSeries
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.util.sortedByTitle
import com.takeoutfm.shared.video.ProgressSource
import kotlin.coroutines.cancellation.CancellationException

/** A podcast ("ps-<id>"). */
data class PodcastSeries(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val imageUri: String,
)

/** Where a podcast episode stands for this user. */
sealed interface EpisodeProgress {
    data object NotStarted : EpisodeProgress
    /** [fraction] 0–1 and minutes left. */
    data class Started(val fraction: Float, val minutesLeft: Int) : EpisodeProgress
    data object Complete : EpisodeProgress
}

/** A podcast episode ("pe-<id>"); [eid] (its GUID) keys progress. */
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

data class PodcastDetails(
    val series: PodcastSeries,
    val subscribed: Boolean,
    val episodes: List<PodcastEpisode>,
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
    /** Every podcast on the server (A–Z), or only the subscribed ones. */
    suspend fun series(subscribedOnly: Boolean): List<PodcastSeries> {
        val view = if (subscribedOnly) api.subscribedPodcasts() else api.podcasts()
        return view.series.orEmpty().sortedByTitle { it.title }.map { it.toSeries() }
    }

    suspend fun details(seriesId: String): PodcastDetails {
        val id = numericId(seriesId, SeriesPrefix)
        val view = api.podcastSeries(id)
        val subscribed = orNull { api.subscribedPodcasts().series.orEmpty().any { it.id == id } } ?: false
        val offsets = orNull { progress.latest() }.orEmpty()
        val title = view.series.title
        return PodcastDetails(
            series = view.series.toSeries(),
            subscribed = subscribed,
            episodes = view.episodes.orEmpty().map { it.toEpisode(title, offsets[it.eid]) },
        )
    }

    suspend fun setSubscribed(seriesId: String, subscribed: Boolean) =
        api.setSubscribed(numericId(seriesId, SeriesPrefix), subscribed)

    /**
     * [episodes] from [startIndex] as a queue (newest first, as listed), starting where the
     * first one was left unless it's finished.
     */
    suspend fun episodesQueue(title: String, imageUri: String, episodes: List<PodcastEpisode>, startIndex: Int): MusicQueue {
        val start = episodes.getOrNull(startIndex)
        val resumeMs = start?.let { orNull { progress.latest()[it.eid] } }
            ?.takeIf { ProgressRules.shouldResume(it.offset, it.duration) }
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

        /** Not started (or barely), started with time left, or within the end (Complete). */
        fun episodeProgress(offset: ApiOffset?): EpisodeProgress = when {
            offset == null || offset.offset < ProgressRules.MinResumeSeconds -> EpisodeProgress.NotStarted
            ProgressRules.isFinished(offset) -> EpisodeProgress.Complete
            offset.duration > 0 -> EpisodeProgress.Started(
                fraction = offset.offset.toFloat() / offset.duration,
                minutesLeft = ((offset.duration - offset.offset) / 60).coerceAtLeast(1),
            )
            else -> EpisodeProgress.Started(fraction = 0f, minutesLeft = 0)
        }
    }
}
