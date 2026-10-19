package com.google.jetstream.data.entities

/** One TV episode; [id] is navigable like a movie id ("e-<id>"). */
data class Episode(
    val id: String,
    val season: Int,
    val episode: Int,
    val name: String,
    val description: String,
    val stillUri: String,
    val duration: String,
)

data class Season(
    val number: Int,
    val episodes: List<Episode>,
)
