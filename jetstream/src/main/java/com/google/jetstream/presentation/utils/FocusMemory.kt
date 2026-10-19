package com.google.jetstream.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged

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
