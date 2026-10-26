package com.takeoutfm.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Podcast wire models (/api/podcasts, /api/series, /api/episodes), PascalCase like the rest.

@Serializable
data class ApiPodcastSeries(
    @SerialName("ID") val id: Int = 0,
    @SerialName("SID") val sid: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("Author") val author: String = "",
    @SerialName("Image") val image: String = "",
    @SerialName("Date") val date: String = "",
)

@Serializable
data class ApiPodcastEpisode(
    @SerialName("ID") val id: Int = 0,
    @SerialName("SID") val sid: String = "",
    /** The episode's GUID; also its media ETag for progress. */
    @SerialName("EID") val eid: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Author") val author: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("Size") val size: Long = 0,
    @SerialName("Date") val date: String = "",
    @SerialName("Image") val image: String = "",
)

@Serializable
data class ApiPodcastsView(
    @SerialName("Series") val series: List<ApiPodcastSeries>? = null,
)

@Serializable
data class ApiPodcastSeriesView(
    @SerialName("Series") val series: ApiPodcastSeries = ApiPodcastSeries(),
    @SerialName("Episodes") val episodes: List<ApiPodcastEpisode>? = null,
)
