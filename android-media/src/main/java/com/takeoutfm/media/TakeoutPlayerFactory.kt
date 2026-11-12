package com.takeoutfm.media

import android.content.Context
import android.net.Uri
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
import java.io.IOException

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
    /** Where Takeout's stream URLs redirect, so each file's opens after the first skip Takeout. */
    val redirects = RedirectCache()

    /**
     * Takeout streams, authorized with the media token (only for the signed-in server). Opens
     * go straight to the storage URL a stream redirects to while it's kept ([RedirectCache]);
     * if that can't be found out, the stream URL is opened and its redirect followed as before.
     */
    @OptIn(UnstableApi::class)
    val upstream: DataSource.Factory by lazy {
        val resolving = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()),
            ResolvingDataSource.Resolver { dataSpec ->
                val uri = dataSpec.uri.toString()
                val auth = session.mediaAuthorization(uri) ?: return@Resolver dataSpec
                val target = try {
                    redirects.target(uri, auth)
                } catch (e: IOException) {
                    null
                }
                if (target != null) {
                    // presigned: storage wants no token of Takeout's
                    dataSpec.withUri(Uri.parse(target))
                } else {
                    dataSpec.withAdditionalHeaders(mapOf("Authorization" to auth))
                }
            }
        )
        RefreshingDataSource.Factory(resolving, redirects)
    }

    /**
     * A player for Takeout streams; [cache] reads files kept on the device by their ETag (the
     * media items' custom cache key) and, for music and podcasts, keeps them as they play
     * ([video] only reads: downloaded videos play from it, others stream).
     */
    @OptIn(UnstableApi::class)
    fun builder(cache: TakeoutMediaCache? = null, video: Boolean = false): ExoPlayer.Builder {
        val dataSourceFactory = (if (video) cache?.videoDataSourceFactory else cache?.playbackDataSourceFactory) ?: upstream
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
