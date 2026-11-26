package com.takeoutfm.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.takeoutfm.shared.music.LiveRadio
import com.takeoutfm.shared.music.LiveSlot
import com.takeoutfm.shared.music.LiveStart
import com.takeoutfm.shared.music.TakeoutMusicRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Follows a live radio station (RADIO-8…12) in the music service, so it works from every entry
 * point (the app, Android Auto, voice, the notification): each track plays to its end and the next
 * from its top; more of the schedule is fetched as tracks start and appended; a track that starts
 * more than a few seconds off the schedule seeks to where the station is; resuming after a short
 * pause plays on behind live ([behindLive]), a long one tunes in again; and a stale queue (an
 * old Recently Played entry, repeat) tunes in again. Live items are the ones with a [LiveSlot].
 */
internal class LiveFollower(
    private val player: Player,
    private val music: TakeoutMusicRepository,
    private val scope: CoroutineScope,
    /** Told when [behindLive] changes, for the app's player ("Behind live · Jump to live"). */
    private val onBehindChanged: (Boolean) -> Unit,
) : Player.Listener {
    /** Playing a delayed copy of the station, after resuming from a pause. */
    var behindLive = false
        private set(value) {
            if (field != value) {
                field = value
                onBehindChanged(value)
            }
        }

    private var pausedAt: Long? = null
    private var fetching: Job? = null
    private var tuning: Job? = null

    fun attach() = player.addListener(this)

    fun detach() = player.removeListener(this)

    private val now: Long get() = System.currentTimeMillis()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val slot = mediaItem?.liveSlot
        if (slot == null) {
            behindLive = false
            pausedAt = null
            return
        }
        if (tuning?.isActive == true) return
        // A natural move to the next track starts at 0; a new queue at its start position.
        val position = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) player.currentPosition else 0L
        when (val start = LiveRadio.check(slot, position, now, behindLive)) {
            LiveStart.AsIs -> Unit
            is LiveStart.SeekTo -> player.seekTo(start.positionMs)
            LiveStart.JumpToLive -> {
                jumpToLive()
                return
            }
        }
        fetchMore()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val slot = player.currentMediaItem?.liveSlot ?: return
        if (!playWhenReady) {
            if (pausedAt == null) pausedAt = now
            return
        }
        if (pausedAt == null) return
        pausedAt = null
        val position = player.currentPosition
        val behind = LiveRadio.behindMs(slot, position, now)
        when {
            behind <= LiveRadio.DriftMs -> Unit
            LiveRadio.keepBehind(slot, position, behind) -> behindLive = true
            else -> jumpToLive()
        }
    }

    /** Tunes in to the station that's playing again: the track on now, from where it is. */
    fun jumpToLive() {
        val slot = player.currentMediaItem?.liveSlot ?: return
        tuning?.cancel()
        fetching?.cancel()
        tuning = scope.launch {
            val queue = try {
                music.liveQueue(slot.stationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            // Same items as the queue's Recently Played entry, so it keeps saving there.
            val historyId = player.currentMediaItem?.historyId
            behindLive = false
            pausedAt = null
            player.setMediaItems(queue.items.map { it.toMediaItem(historyId) }, 0, queue.startPositionMs)
            player.prepare()
            player.play()
        }
        tuning?.invokeOnCompletion { if (tuning?.isActive != true) fetchMore() }
    }

    /**
     * Appends the schedule's next tracks when the queue is running short; each track start asks
     * (RADIO.md: refetch as each track starts), and a failure waits for the next one.
     */
    private fun fetchMore() {
        if (fetching?.isActive == true) return
        val queued = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toQueueItem().takeIf { q -> q.live != null } }
        if (queued.isEmpty()) return
        val historyId = player.currentMediaItem?.historyId
        fetching = scope.launch {
            val more = try {
                music.liveMore(queued)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch // played on; the next track asks again
            }
            // After what's queued now, on the same station.
            val last = (player.mediaItemCount - 1).takeIf { it >= 0 }?.let { player.getMediaItemAt(it).liveSlot } ?: return@launch
            val fresh = more.filter { item -> item.live?.let { it.stationId == last.stationId && it.startMs > last.startMs } == true }
            if (fresh.isNotEmpty()) player.addMediaItems(fresh.map { it.toMediaItem(historyId) })
        }
    }
}
