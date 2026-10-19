package com.google.jetstream.data.entities

/** Search hits by kind; any list may be empty. */
data class SearchResults(
    val movies: MovieList = emptyList(),
    val shows: MovieList = emptyList(),
    val episodes: MovieList = emptyList(),
) {
    val isEmpty get() = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty()
}
