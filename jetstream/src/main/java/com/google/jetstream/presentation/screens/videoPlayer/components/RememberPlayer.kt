/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jetstream.presentation.screens.videoPlayer.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.jetstream.data.takeout.TakeoutSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberPlayer(context: Context) = remember {
    val session = EntryPointAccessors
        .fromApplication(context.applicationContext, TakeoutSessionEntryPoint::class.java)
        .session()
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
    ExoPlayer.Builder(context, renderersFactory)
        .setSeekForwardIncrementMs(10_000)
        .setSeekBackIncrementMs(10_000)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
        )
        .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        .build()
        .apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
}
