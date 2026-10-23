package com.google.jetstream.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which item of a lazy list/grid last had focus, kept across leaving the screen (e.g. opening a
 * title and pressing Back), so focus can go back to that item instead of whichever one is
 * geometrically nearest.
 */
@Stable
class FocusMemory internal constructor(
    internal val lastKey: MutableState<String?>,
    internal val requester: FocusRequester,
) {
    /** True while the remembered item is composed, i.e. focus can actually be sent to it. */
    internal var isAttached by mutableStateOf(false)
}

@Composable
fun rememberFocusMemory(): FocusMemory {
    val lastKey = rememberSaveable { mutableStateOf<String?>(null) }
    return remember { FocusMemory(lastKey, FocusRequester()) }
}

/**
 * A [FocusMemory] keeping its item in [lastKey], e.g. state held by a ViewModel so it survives
 * the screen reloading (and showing a skeleton) when it's returned to.
 */
@Composable
fun rememberFocusMemory(lastKey: MutableState<String?>): FocusMemory =
    remember(lastKey) { FocusMemory(lastKey, FocusRequester()) }

/**
 * For screens that otherwise put focus on their first control: when the screen is composed
 * again (coming back to it) with an item remembered, sends focus to that item once it's laid
 * out. Gives up quietly if it doesn't appear.
 */
@Composable
fun RestoreRememberedFocus(memory: FocusMemory) {
    LaunchedEffect(memory) {
        if (memory.lastKey.value == null) return@LaunchedEffect
        withTimeoutOrNull(RestoreTimeoutMillis) {
            snapshotFlow { memory.isAttached }.first { it }
        } ?: return@LaunchedEffect
        // The item is composed; it may not be placed until the next frame.
        withTimeoutOrNull(RestoreTimeoutMillis) {
            while (!memory.requester.requestFocusOrFalse()) delay(16)
        }
    }
}

private fun FocusRequester.requestFocusOrFalse(): Boolean =
    try {
        requestFocus()
        true
    } catch (e: IllegalStateException) {
        false
    }

private const val RestoreTimeoutMillis = 2_000L

/** On the list/grid: when focus enters it, go to the remembered item if there is one. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.restoreFocus(memory: FocusMemory): Modifier = focusRestorer {
    if (memory.isAttached) memory.requester else FocusRequester.Default
}

/** On each item: records it when focused, and is the target when it's the remembered one. */
@Composable
fun Modifier.rememberFocus(memory: FocusMemory, key: String): Modifier {
    val isRemembered = memory.lastKey.value == key
    if (isRemembered) {
        DisposableEffect(memory) {
            memory.isAttached = true
            onDispose { memory.isAttached = false }
        }
    }
    return this
        .then(if (isRemembered) Modifier.focusRequester(memory.requester) else Modifier)
        .onFocusChanged { if (it.hasFocus || it.isFocused) memory.lastKey.value = key }
}
