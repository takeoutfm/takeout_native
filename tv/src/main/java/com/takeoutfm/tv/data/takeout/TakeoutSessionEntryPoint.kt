package com.takeoutfm.tv.data.takeout

import com.takeoutfm.media.TakeoutPlayerFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets non-Hilt code (e.g. the video player's `rememberPlayer`) reach app singletons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TakeoutSessionEntryPoint {
    fun playerFactory(): TakeoutPlayerFactory
}
