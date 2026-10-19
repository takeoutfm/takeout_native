package com.google.jetstream.data.entities

/** One of the server's recommendation sets, e.g. "Halloween" in October. */
data class Recommendation(
    val name: String,
    val movies: MovieList,
)
