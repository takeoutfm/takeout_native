package com.takeoutfm.media.auto

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.takeoutfm.media.toMediaItem
import com.takeoutfm.shared.music.MusicHistory
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.StationKind
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import kotlinx.coroutines.flow.first

/**
 * What Android Auto (and any media browser) can browse and play: Recently Played, new and
 * recently added releases, radio (live streams first), playlists and subscribed podcasts, plus
 * search. Folders are browsable items; playable items have ids [PlayPrefix]…, which
 * [queue] expands into the whole queue they stand for (an album, a station, an episode list…).
 */
class TakeoutBrowseTree(
    private val music: TakeoutMusicRepository,
    private val podcasts: TakeoutPodcastRepository,
    private val history: MusicHistory,
    /** Titles for the root's folders (string resources live in the app). */
    private val titles: Titles,
) {
    data class Titles(
        val recent: String,
        val newReleases: String,
        val recentlyAdded: String,
        val radio: String,
        val playlists: String,
        val podcasts: String,
    )

    fun root(): MediaItem = folder(Root, "TakeoutFM", grid = false)

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == Root -> listOf(
            folder(Recent, titles.recent, grid = true),
            folder(New, titles.newReleases, grid = true),
            folder(Added, titles.recentlyAdded, grid = true),
            folder(Radio, titles.radio, grid = false),
            folder(Playlists, titles.playlists, grid = false),
            folder(Podcasts, titles.podcasts, grid = true),
        )
        parentId == Recent -> history.entries.value.map {
            playable("${PlayPrefix}recent:${it.id}", it.title, it.creator, it.coverUri)
        }
        parentId == New -> music.newReleases().first().map {
            playable("${PlayPrefix}album:${it.id}", it.name, it.artist, it.coverUri)
        }
        parentId == Added -> music.recentlyAdded().first().map {
            playable("${PlayPrefix}album:${it.id}", it.name, it.artist, it.coverUri)
        }
        parentId == Radio -> music.radio()
            .sortedBy { it.kind != StationKind.Stream }
            .flatMap { it.stations }
            .map { playable("${PlayPrefix}station:${it.id}", it.name, it.description, it.imageUri) }
        parentId == Playlists -> music.playlists().map {
            playable("${PlayPrefix}playlist:${it.id}", it.name, "", "")
        }
        parentId == Podcasts -> podcasts.series(subscribedOnly = true).map {
            folder("$PodcastPrefix${it.id}", it.title, grid = false, subtitle = it.author, imageUri = it.imageUri)
        }
        parentId.startsWith(PodcastPrefix) -> {
            val details = podcasts.details(parentId.removePrefix(PodcastPrefix))
            details.episodes.mapIndexed { i, e ->
                playable("${PlayPrefix}episode:${details.series.id}:$i", e.title, details.series.title, e.imageUri.ifEmpty { details.series.imageUri })
            }
        }
        else -> emptyList()
    }

    /** Albums, tracks and stations matching [query] (the server's search syntax works). */
    suspend fun search(query: String): List<MediaItem> {
        val r = music.search(query)
        return r.albums.map { playable("${PlayPrefix}album:${it.id}", it.name, it.artist, it.coverUri) } +
            r.tracks.map { playable("${PlayPrefix}track:${it.id}", it.title, it.artist, it.coverUri) } +
            r.stations.map { playable("${PlayPrefix}station:${it.id}", it.name, it.description, it.imageUri) }
    }

    /** The queue a playable id stands for, or null when it isn't one of ours. */
    suspend fun queue(mediaId: String): MusicQueue? {
        if (!mediaId.startsWith(PlayPrefix)) return null
        val (kind, rest) = mediaId.removePrefix(PlayPrefix).split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (kind) {
            "recent" -> history.find(rest)?.toQueue()
            "album" -> music.albumQueue(rest)
            "station" -> music.stationQueue(rest)
            "playlist" -> music.playlistQueue(rest)
            "track" -> music.trackQueue(rest)
            "episode" -> {
                val seriesId = rest.substringBeforeLast(":")
                val index = rest.substringAfterLast(":").toIntOrNull() ?: 0
                val details = podcasts.details(seriesId)
                podcasts.episodesQueue(details.series.title, details.series.imageUri, details.episodes, index)
            }
            else -> null
        }
    }

    /** "Play X" from the car's voice search: the server's best match, as the Takeout apps. */
    suspend fun searchQueue(query: String): MusicQueue? {
        if (query.isBlank()) return null
        val best = if (query.contains(':')) "" else "&m=1"
        val ref = "/music/search?q=" + encode(query.trim()) + best
        return music.refQueue(ref, query.trim()).takeIf { it.items.isNotEmpty() }
    }

    /** Records [queue] in Recently Played and gives its media items, as the app's player does. */
    fun startPlaying(queue: MusicQueue): List<MediaItem> {
        history.record(queue)
        return queue.items.map { it.toMediaItem() }
    }

    private fun folder(id: String, title: String, grid: Boolean, subtitle: String = "", imageUri: String = "") =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle.ifEmpty { null })
                    .setArtworkUri(imageUri.ifEmpty { null }?.let(Uri::parse))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(
                        Bundle().apply {
                            putInt(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                                if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                            )
                        }
                    )
                    .build()
            )
            .build()

    private fun playable(id: String, title: String, subtitle: String, imageUri: String) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle.ifEmpty { null })
                    .setSubtitle(subtitle.ifEmpty { null })
                    .setArtworkUri(imageUri.ifEmpty { null }?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (b >= 0 && (c.isLetterOrDigit() || c in "-_.~")) append(c) else append("%%%02X".format(b.toInt() and 0xff))
        }
    }

    companion object {
        const val Root = "root"
        const val Recent = "recent"
        const val New = "new"
        const val Added = "added"
        const val Radio = "radio"
        const val Playlists = "playlists"
        const val Podcasts = "podcasts"
        const val PodcastPrefix = "podcast:"
        const val PlayPrefix = "play:"
    }
}
