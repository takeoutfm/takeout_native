package com.takeoutfm.wear.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.takeoutfm.media.MusicPlayer
import com.takeoutfm.media.NowPlaying
import com.takeoutfm.shared.listen.parseStreamTitle
import com.takeoutfm.wear.MainActivity
import com.takeoutfm.wear.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A watch-face complication showing what's playing: the title and artist (a live stream's
 * current song), with a play or pause icon; "Not playing" when nothing is queued. Tapping opens
 * the app. Pushed a new value whenever the song or play/pause changes ([requestUpdate], from
 * WatchApp), so it never polls.
 */
@AndroidEntryPoint
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {
    @Inject lateinit var player: MusicPlayer

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // It can be the first thing in a fresh process: wait for the player's state.
        withTimeoutOrNull(3_000) { player.awaitConnected() }
        return data(request.complicationType, Shown.of(player.state.value))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, Shown("Ride Lonesome", "Beck", playing = true))

    /** What the complication shows. */
    private data class Shown(val title: String, val artist: String, val playing: Boolean) {
        companion object {
            fun of(state: NowPlaying): Shown? {
                val item = state.current ?: return null
                // A stream's song ("Artist - Title"), else the station.
                val onAir = state.streamTitle?.let(::parseStreamTitle)
                return Shown(
                    title = onAir?.second ?: state.streamTitle ?: item.title,
                    artist = onAir?.first ?: item.artist,
                    playing = state.playWhenReady,
                )
            }
        }
    }

    private fun data(type: ComplicationType, shown: Shown?): ComplicationData? {
        val title = shown?.title ?: getString(R.string.not_playing)
        val artist = shown?.artist.orEmpty()
        val description = PlainComplicationText.Builder(
            listOf(getString(R.string.now_playing), title, artist).filter { it.isNotEmpty() }.joinToString(", ")
        ).build()
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(
                this,
                when {
                    shown == null -> R.drawable.ic_music
                    shown.playing -> R.drawable.ic_play
                    else -> R.drawable.ic_pause
                },
            )
        ).build()
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return when (type) {
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(PlainComplicationText.Builder(title).build(), description)
                    .apply { if (artist.isNotEmpty()) setTitle(PlainComplicationText.Builder(artist).build()) }
                    .setMonochromaticImage(icon)
                    .setTapAction(open)
                    .build()
            // Short text has room for about 7 characters: the app's name when nothing's queued.
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(if (shown == null) "Takeout" else title).build(), description)
                    .setMonochromaticImage(icon)
                    .setTapAction(open)
                    .build()
            else -> null
        }
    }

    companion object {
        /** Asks every watch face showing it for a new value (the song or play/pause changed). */
        fun requestUpdate(context: Context) =
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, NowPlayingComplicationService::class.java))
                .requestUpdateAll()
    }
}
