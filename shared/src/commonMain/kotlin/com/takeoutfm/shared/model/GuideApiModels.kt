package com.takeoutfm.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Channel guide wire models (/api/guide), PascalCase like the rest.

@Serializable
data class ApiGuideView(
    /** The server's clock, epoch ms; offsets into programs use it, not the device's. */
    @SerialName("Time") val time: Long = 0,
    @SerialName("Start") val start: Instant? = null,
    @SerialName("End") val end: Instant? = null,
    @SerialName("Channels") val channels: List<ApiGuideChannel>? = null,
)

@Serializable
data class ApiGuideChannel(
    /** Unique; a channel's identity. */
    @SerialName("Name") val name: String = "",
    @SerialName("Programs") val programs: List<ApiGuideProgram>? = null,
)

@Serializable
data class ApiGuideProgram(
    /** "movie", "episode" or "offair" (the title has left the library). */
    @SerialName("Kind") val kind: String = "",
    @SerialName("Block") val block: String? = null,
    @SerialName("Start") val start: Instant,
    @SerialName("End") val end: Instant,
    @SerialName("Title") val title: String = "",
    @SerialName("Subtitle") val subtitle: String? = null,
    @SerialName("Rating") val rating: String? = null,
    @SerialName("Image") val image: String? = null,
    @SerialName("Background") val background: String? = null,
    @SerialName("ETag") val etag: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Location") val location: String? = null,
    /** The movie or episode row, per [kind]. */
    @SerialName("TargetID") val targetId: Int? = null,
    @SerialName("TMID") val tmid: Long? = null,
    @SerialName("TVID") val tvid: Long? = null,
    @SerialName("Season") val season: Int? = null,
    @SerialName("Episode") val episode: Int? = null,
)
