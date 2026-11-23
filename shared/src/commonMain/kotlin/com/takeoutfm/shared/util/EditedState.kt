package com.takeoutfm.shared.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Server state that several pages show and edit (decision 017): loads set it, edits change it,
 * pages observe [flow].
 *
 * A load that overlapped an edit is dropped. Its answer may predate the edit: pressing Back
 * right after Unsubscribe reloaded the subscriptions before the server had done it, and the
 * stale answer put the podcast back. The edit has already changed the state, and the next load
 * brings anything else the server has.
 */
class EditedState<T>(initial: T) {
    private val state = MutableStateFlow(initial)
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    /** Bumped as each edit starts and again as it ends. */
    private val edits = MutableStateFlow(0L)

    /** Runs [fetch] and makes its answer the state, unless an edit started or ended meanwhile. */
    suspend fun <R> load(fetch: suspend () -> R, toState: (R) -> T): R {
        val before = edits.value
        val answer = fetch()
        if (edits.value == before) state.value = toState(answer)
        return answer
    }

    /** Runs [block], an edit: [update] and [set] inside it change the state as it goes. */
    suspend fun <R> edit(block: suspend () -> R): R {
        edits.update { it + 1 }
        try {
            return block()
        } finally {
            edits.update { it + 1 }
        }
    }

    fun update(change: (T) -> T) = state.update(change)

    fun set(value: T) {
        state.value = value
    }
}
