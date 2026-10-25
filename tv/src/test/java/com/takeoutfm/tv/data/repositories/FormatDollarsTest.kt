package com.takeoutfm.tv.data.repositories

import com.takeoutfm.tv.data.repositories.TakeoutMovieRepository.Companion.formatDollars
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDollarsTest {
    @Test
    fun formatsWholeDollarsWithCommas() {
        assertEquals("$325,000", formatDollars(325_000))
        assertEquals("$47,000,000", formatDollars(47_000_000))
        assertEquals("$2,923,706,026", formatDollars(2_923_706_026))
        assertEquals("$999", formatDollars(999))
    }

    @Test
    fun unknownAmountsShowADash() {
        assertEquals("—", formatDollars(0))
    }
}
