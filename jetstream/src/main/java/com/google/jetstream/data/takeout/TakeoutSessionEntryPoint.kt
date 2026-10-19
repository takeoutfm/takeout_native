package com.google.jetstream.data.takeout

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets non-Hilt code (e.g. the player factory) reach the session. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TakeoutSessionEntryPoint {
    fun session(): TakeoutSession
}
