package com.takeoutfm.shared.api

import com.takeoutfm.shared.model.ApiAccessCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock

/**
 * A sign-in code from GET /api/code ([TakeoutApi.requestCode]): [code] to show, approved at
 * [link] until [expiresAtMs] (this device's clock, counted from when the code arrived). Apps
 * show the time left with [remaining] (or [secondsLeft]) and, once it's 0 or [TakeoutApi.awaitCode] gives up,
 * that the code expired, with a button for a new one.
 */
class DeviceCode internal constructor(
    val endpoint: String,
    private val access: ApiAccessCode,
    receivedAtMs: Long,
) {
    val code: String get() = access.code
    val link: String get() = "$endpoint/link"
    internal val accessToken: String get() = access.accessToken

    /** Seconds the code is valid from when it arrived. */
    val expiresIn: Int = access.expiresIn.takeIf { it > 0 } ?: DefaultExpiresIn
    val expiresAtMs: Long = receivedAtMs + expiresIn * 1_000L
    val intervalMs: Long = (access.interval.takeIf { it > 0 } ?: DefaultInterval) * 1_000L

    /** Whole seconds until the code expires, rounded up; 0 once it has. */
    fun secondsLeft(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Int =
        ((expiresAtMs - nowMs + 999) / 1_000).coerceAtLeast(0).toInt()

    /** [secondsLeft] now and each time it changes, ending with 0. */
    fun remaining(): Flow<Int> = flow {
        while (true) {
            val ms = expiresAtMs - Clock.System.now().toEpochMilliseconds()
            val left = secondsLeft(expiresAtMs - ms)
            emit(left)
            if (left == 0) break
            delay((ms - 1).mod(1_000L) + 1) // until the next whole second
        }
    }

    companion object {
        /** Older servers don't say; their codes last the default Auth.CodeAge. */
        const val DefaultExpiresIn = 5 * 60
        const val DefaultInterval = 5

        /** [seconds] as m:ss, e.g. "4:05". */
        fun countdown(seconds: Int): String {
            val s = seconds.coerceAtLeast(0)
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }
    }
}
