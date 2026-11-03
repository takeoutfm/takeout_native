package com.takeoutfm.tv.presentation.screens.dashboard

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.focus.FocusRequester

/**
 * Focus requesters for the top bar: the profile avatar's at 0, then one per tab.
 *
 * Tabs come and go with the library index (no Shows tab without shows, a recommendation set
 * ending), and the tab row composes its tabs late, during layout. A requester that isn't on a tab
 * yet (or any more) throws "FocusRequester is not initialized" when asked for focus, so the
 * requesters stay the same objects as the list changes, and each slot reports when its item is
 * on screen ([attach]/[detach]); only attached ones are handed out ([target], [requestFocus]).
 */
@Stable
class TopBarFocus {
    private val requesters = mutableListOf<FocusRequester>()
    private val attached = mutableStateMapOf<Int, Unit>()

    /** The requester for slot [index], made on first use and the same object from then on. */
    operator fun get(index: Int): FocusRequester {
        while (requesters.size <= index) requesters += FocusRequester()
        return requesters[index]
    }

    fun attach(index: Int) {
        attached[index] = Unit
    }

    fun detach(index: Int) {
        attached.remove(index)
    }

    /** Whether slot [index]'s item is on screen. Snapshot state, so it can be waited on. */
    fun isAttached(index: Int) = index in attached

    /** Slot [index]'s requester if its item is on screen, else null. */
    fun target(index: Int): FocusRequester? = if (isAttached(index)) get(index) else null

    /** Focuses slot [index] if its item is on screen; returns whether it asked. */
    fun requestFocus(index: Int): Boolean {
        val requester = target(index) ?: return false
        requester.requestFocus()
        return true
    }
}
