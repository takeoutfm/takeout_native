package com.takeoutfm.shared.db

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A fresh in-memory database. */
expect fun testDb(): TakeoutDb

/** Waits (in real time) until [check] holds; the stores write on background threads. */
suspend fun eventually(check: () -> Boolean) = withContext(Dispatchers.Default) {
    withTimeout(5.seconds) {
        while (!check()) delay(10)
    }
}
