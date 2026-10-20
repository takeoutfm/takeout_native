package com.google.jetstream.data.repositories

import com.google.jetstream.data.entities.MovieCast
import com.google.jetstream.data.repositories.TakeoutMovieRepository.Companion.withDirectorsFirst
import org.junit.Assert.assertEquals
import org.junit.Test

class WithDirectorsFirstTest {
    private fun person(id: String, role: String = "") = MovieCast(id, role, "Name $id", "")

    @Test
    fun directorsComeFirstLabelledDirector() {
        val result = withDirectorsFirst(
            directors = listOf(person("9"), person("8")),
            cast = listOf(person("1", "Ben"), person("2", "Barbra")),
        )
        assertEquals(listOf("9", "8", "1", "2"), result.map { it.id })
        assertEquals(listOf("Director", "Director", "Ben", "Barbra"), result.map { it.characterName })
    }

    @Test
    fun directorWhoActsGetsOneCard() {
        val result = withDirectorsFirst(
            directors = listOf(person("5")),
            cast = listOf(person("1", "Ben"), person("5", "Neil")),
        )
        assertEquals(listOf("5", "1"), result.map { it.id })
        assertEquals("Director · as Neil", result.first().characterName)
    }

    @Test
    fun repeatedDirectorsAreMerged() {
        val result = withDirectorsFirst(
            directors = listOf(person("5"), person("5")),
            cast = emptyList(),
        )
        assertEquals(listOf("5"), result.map { it.id })
    }
}
