package com.takeoutfm.shared.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs database writes off the caller's thread, one at a time and in the order they were
 * asked for, so a later write for the same row always lands last.
 */
class DbWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    fun write(block: () -> Unit) {
        scope.launch { runCatching(block) }
    }
}
