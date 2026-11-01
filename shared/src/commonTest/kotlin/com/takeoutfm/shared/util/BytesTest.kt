package com.takeoutfm.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals

class BytesTest {
    @Test
    fun formats() {
        assertEquals("1 KB", formatBytes(10))
        assertEquals("12 MB", formatBytes(12L * 1024 * 1024 + 500))
        assertEquals("1.0 GB", formatBytes(1L shl 30))
        assertEquals("1.4 GB", formatBytes((1.44 * (1L shl 30)).toLong()))
        assertEquals("23.5 GB", formatBytes((23.46 * (1L shl 30)).toLong()))
    }
}
