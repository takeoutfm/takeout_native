package com.takeoutfm.mobile.voice

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.mobile.R
import com.takeoutfm.mobile.ui.video.VideoPlayerActivity
import com.takeoutfm.shared.music.TakeoutMusicRepository
import com.takeoutfm.shared.video.TakeoutVideoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Carries out Assistant requests ([VoiceRequests]) with the music player and the library. */
@Singleton
class VoiceHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val player: MusicPlayer,
    private val music: TakeoutMusicRepository,
    private val video: TakeoutVideoRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** True when [intent] was a voice request (now being handled). */
    fun handle(intent: Intent?): Boolean {
        intent ?: return false
        val extras = intent.extras?.keySet().orEmpty().associateWith { intent.extras?.get(it)?.toString() }
        val request = VoiceRequests.parse(intent.action, extras) ?: return false
        scope.launch {
            try {
                run(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(R.string.cant_play)
            }
        }
        return true
    }

    private suspend fun run(request: VoiceRequest) {
        when (request) {
            is VoiceRequest.PlayRef -> {
                val queue = music.refQueue(request.ref, request.title)
                if (queue.items.isEmpty()) toast(R.string.no_results) else player.play(queue)
            }
            is VoiceRequest.PlayStation -> {
                val stations = music.radio().flatMap { it.stations }
                val station = stations.firstOrNull { it.name.equals(request.name, ignoreCase = true) }
                    ?: stations.firstOrNull { it.name.contains(request.name, ignoreCase = true) }
                if (station == null) toast(R.string.no_results) else player.play(music.stationQueue(station.id))
            }
            is VoiceRequest.PlayMovie -> {
                val movie = video.searchMovies(request.title).firstOrNull()
                if (movie == null) toast(R.string.no_results) else VideoPlayerActivity.start(context, movie.id, startOver = false)
            }
            VoiceRequest.Play -> if (player.state.value.playWhenReady.not()) player.playPause()
            VoiceRequest.Pause -> if (player.state.value.playWhenReady) player.playPause()
            VoiceRequest.Next -> player.next()
        }
    }

    private fun toast(message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
