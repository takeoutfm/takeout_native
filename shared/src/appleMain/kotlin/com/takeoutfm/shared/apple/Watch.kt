package com.takeoutfm.shared.apple

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** A running [watch]; [cancel] stops it (e.g. when the Swift view model goes away). */
class Watching internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Calls [onEach] on the main thread with every value of [flow] (a StateFlow's current value
 * first), so SwiftUI can follow shared state without a Kotlin–Swift coroutine bridge.
 */
fun <T> watch(flow: Flow<T>, onEach: (T) -> Unit): Watching =
    Watching(main.launch { flow.collect { onEach(it) } })
