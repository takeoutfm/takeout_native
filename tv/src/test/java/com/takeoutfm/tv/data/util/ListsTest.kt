package com.takeoutfm.tv.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ListsTest {
    @Test
    fun aToZIgnoresCaseAccentsAndPunctuation() {
        val sorted = listOf("Airplane II", "amélie", "Airplane!", "Zodiac", "Alien")
            .sortedByTitle { it }
        assertEquals(listOf("Airplane!", "Airplane II", "Alien", "amélie", "Zodiac"), sorted)
    }

    @Test
    fun sortKeyFoldsToPlainLowercaseWords() {
        assertEquals("amelie", sortKey("Amélie"))
        assertEquals("airplane ii", sortKey("Airplane: II"))
    }
}
