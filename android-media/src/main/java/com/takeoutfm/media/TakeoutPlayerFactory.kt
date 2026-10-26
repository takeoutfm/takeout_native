package com.takeoutfm.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import com.takeoutfm.media.offline.TakeoutMediaCache
import com.takeoutfm.shared.api.TakeoutSession

/**
 * ExoPlayer set up for Takeout streams, shared by video and music playback, so stream
 * authorization lives in one place. Callers add what's specific to them (seek steps, scaling,
 * audio attributes) to the returned builder. [debugLogging] turns on [addDebugLogging] (debug
 * builds).
 */
class TakeoutPlayerFactory(
    private val context: Context,
    private val session: TakeoutSession,
    private val debugLogging: Boolean,
) {
    /** Takeout streams, authorized with the media token (only for the signed-in server). */
    @OptIn(UnstableApi::class)
    val upstream: DataSource.Factory by lazy {
        ResolvingDataSource.Factory(
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
    }

    /**
     * A player for Takeout streams; [cache] (music and podcasts) reads tracks kept on the
     * device by their ETag (the media items' custom cache key) and keeps them as they play.
     */
    @OptIn(UnstableApi::class)
    fun builder(cache: TakeoutMediaCache? = null): ExoPlayer.Builder {
        val dataSourceFactory = cache?.playbackDataSourceFactory ?: upstream
        // Try another decoder before failing, e.g. when the hardware one can't init this stream.
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
    }

    /** Logs tracks (with decoder support), formats and errors to logcat as "EventLogger". */
    @OptIn(UnstableApi::class)
    fun addDebugLogging(player: ExoPlayer) {
        if (debugLogging) player.addAnalyticsListener(EventLogger())
    }
}
