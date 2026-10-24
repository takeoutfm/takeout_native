package com.google.jetstream.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import com.google.jetstream.BuildConfig
import com.google.jetstream.data.takeout.TakeoutSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExoPlayer set up for Takeout streams, shared by the video player and (later) music playback,
 * so stream authorization lives in one place. Callers add what's specific to them (seek steps,
 * scaling, audio attributes) to the returned builder.
 */
@Singleton
class TakeoutPlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: TakeoutSession,
) {
    @OptIn(UnstableApi::class)
    fun builder(): ExoPlayer.Builder {
        // Takeout streams are authorized with the media token, only for the signed-in server.
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()),
            ResolvingDataSource.Resolver { dataSpec ->
                val auth = session.mediaAuthorization(dataSpec.uri.toString())
                if (auth != null) {
                    dataSpec.withAdditionalHeaders(mapOf("Authorization" to auth))
                } else {
                    dataSpec
                }
            }
        )
        // Try another decoder before failing, e.g. when the hardware one can't init this stream.
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
    }

    /** Logs tracks (with decoder support), formats and errors to logcat as "EventLogger". */
    @OptIn(UnstableApi::class)
    fun addDebugLogging(player: ExoPlayer) {
        if (BuildConfig.DEBUG) player.addAnalyticsListener(EventLogger())
    }
}
