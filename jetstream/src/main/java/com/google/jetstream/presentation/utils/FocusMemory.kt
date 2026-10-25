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
import androidx.compose.runtime.staticCompositionLocalOf
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

/**
 * The item that last had focus on the current screen, for overlays (the mini player) to send
 * focus back to: D-pad searches from an overlay don't reliably find what's under it. Items
 * opt in with [returnFocusTarget]; focus on anything else clears it, so a stale item isn't used.
 */
@Stable
class FocusReturn {
    internal var target: FocusRequester? = null
    internal var setByItem = false
    private var lastFocused: Any? = null

    /** The screen's content as a whole, for when no item is remembered (see [restore]). */
    val content = FocusRequester()

    /** Whether something on the screen (not an overlay) has focus. */
    var contentHasFocus by mutableStateOf(false)
        internal set

    /** Updates [contentHasFocus]; call with the screen's focus changes. */
    fun onContentFocusChanged(hasFocus: Boolean) {
        contentHasFocus = hasFocus
    }

    /**
     * Something on the screen has focus ([focused] identifies it; this is also called as it
     * moves, e.g. while a row scrolls). Forget the item when focus went to something else.
     */
    fun onFocused(focused: Any) {
        if (focused === lastFocused) return
        lastFocused = focused
        if (!setByItem) target = null
        setByItem = false
    }

    /**
     * Sends focus back to the last item, or, when it's gone (e.g. it was on the previous screen),
     * into the screen's first focusable item: searching from an overlay doesn't reach the screen
     * under it, so without this focus could be stuck there. False when the screen has nothing
     * focusable (still loading).
     */
    fun restore(): Boolean {
        target?.let { r ->
            if (runCatching { r.requestFocus() }.isSuccess && contentHasFocus) return true
        }
        target = null
        runCatching { content.requestFocus() }
        return contentHasFocus
    }
}

val LocalFocusReturn = staticCompositionLocalOf<FocusReturn?> { null }

/** Lets an overlay send focus back to this item (see [FocusReturn]). */
@Composable
fun Modifier.returnFocusTarget(): Modifier {
    val holder = LocalFocusReturn.current ?: return this
    val requester = remember { FocusRequester() }
    return this
        .focusRequester(requester)
        .onFocusChanged {
            if (it.hasFocus) {
                holder.target = requester
                holder.setByItem = true
            }
        }
        .also {
            // Gone with its screen: never send focus to a detached item.
            DisposableEffect(requester) {
                onDispose { if (holder.target === requester) holder.target = null }
            }
        }
}
