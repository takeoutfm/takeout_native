package com.takeoutfm.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals

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
