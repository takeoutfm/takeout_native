package com.takeoutfm.tv.presentation.screens.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarFocusTest {
    @Test
    fun requestersStayTheSameAsSlotsAreAdded() {
        val focus = TopBarFocus()
        val avatar = focus[0]
        val third = focus[3]
        focus[8]
        assertSame(avatar, focus[0])
        assertSame(third, focus[3])
    }

    @Test
    fun onlyAttachedSlotsAreHandedOut() {
        val focus = TopBarFocus()
        val tab = focus[2]
        assertNull(focus.target(2))
        assertFalse(focus.requestFocus(2))

        focus.attach(2)
        assertTrue(focus.isAttached(2))
        assertSame(tab, focus.target(2))
    }

    @Test
    fun removedTabIsNotHandedOut() {
        // Shows tab hidden once the index says there are no shows: the last slot goes away.
        val focus = TopBarFocus()
        (0..7).forEach { focus.attach(it) }
        focus.detach(7)
        assertNull(focus.target(7))
        assertFalse(focus.requestFocus(7))
        assertSame(focus[6], focus.target(6))
    }

    @Test
    fun outOfRangeSlotIsNotHandedOut() {
        val focus = TopBarFocus()
        focus.attach(0)
        assertNull(focus.target(-1))
        assertNull(focus.target(5))
    }
}
