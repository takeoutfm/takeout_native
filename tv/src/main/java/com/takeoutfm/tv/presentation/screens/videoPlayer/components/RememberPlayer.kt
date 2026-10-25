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

package com.takeoutfm.tv.presentation.screens.videoPlayer.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.takeoutfm.tv.data.takeout.TakeoutSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberPlayer(context: Context) = remember {
    val factory = EntryPointAccessors
        .fromApplication(context.applicationContext, TakeoutSessionEntryPoint::class.java)
        .playerFactory()
    factory.builder()
        // Takes audio focus, so starting a video pauses music (and calls pause the video).
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setSeekForwardIncrementMs(10_000)
        .setSeekBackIncrementMs(10_000)
        .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        .build()
        .apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            factory.addDebugLogging(this)
        }
}
