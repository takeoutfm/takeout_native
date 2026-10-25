package com.takeoutfm.tv.data.music

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Takeout's music JSON. Like ApiModels.kt: PascalCase names, every field defaulted so a missing
// or newer field never breaks decoding. Dates are RFC 3339 strings ("0001-01-01T…" when unknown).

@Serializable
data class ApiArtist(
    @SerialName("ID") val id: Int = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("SortName") val sortName: String = "",
    @SerialName("ARID") val arid: String = "",
    @SerialName("Disambiguation") val disambiguation: String = "",
    @SerialName("Country") val country: String = "",
    @SerialName("Area") val area: String = "",
    @SerialName("Date") val date: String = "",
    @SerialName("EndDate") val endDate: String = "",
    @SerialName("Genre") val genre: String = "",
    /** Server path of the artist's photo ("/img/fa/…"), or a full URL; may be empty. */
    @SerialName("Image") val image: String = "",
    @SerialName("Background") val background: String = "",
)

@Serializable
data class ApiRelease(
    @SerialName("ID") val id: Int = 0,
    @SerialName("Artist") val artist: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("RGID") val rgid: String = "",
    @SerialName("REID") val reid: String = "",
    @SerialName("Disambiguation") val disambiguation: String = "",
    /** MusicBrainz primary type: "Album", "Single", "EP", … */
    @SerialName("Type") val type: String = "",
    @SerialName("SecondaryType") val secondaryType: String = "",
    /** The release group's first release date. */
    @SerialName("Date") val date: String = "",
    @SerialName("ReleaseDate") val releaseDate: String = "",
    @SerialName("TrackCount") val trackCount: Int = 0,
    @SerialName("DiscCount") val discCount: Int = 0,
    @SerialName("Artwork") val artwork: Boolean = false,
    @SerialName("FrontArtwork") val frontArtwork: Boolean = false,
    @SerialName("OtherArtwork") val otherArtwork: String = "",
    @SerialName("GroupArtwork") val groupArtwork: Boolean = false,
)

@Serializable
data class ApiTrack(
    @SerialName("ID") val id: Int = 0,
    @SerialName("UUID") val uuid: String = "",
    @SerialName("Artist") val artist: String = "",
    @SerialName("Release") val release: String = "",
    @SerialName("Date") val date: String = "",
    @SerialName("TrackNum") val trackNum: Int = 0,
    @SerialName("DiscNum") val discNum: Int = 0,
    @SerialName("Title") val title: String = "",
    @SerialName("ETag") val etag: String = "",
    @SerialName("TrackCount") val trackCount: Int = 0,
    @SerialName("DiscCount") val discCount: Int = 0,
    @SerialName("REID") val reid: String = "",
    @SerialName("RGID") val rgid: String = "",
    @SerialName("ReleaseTitle") val releaseTitle: String = "",
    /** The artist with any featured artists. */
    @SerialName("TrackArtist") val trackArtist: String = "",
    @SerialName("Artwork") val artwork: Boolean = false,
    @SerialName("FrontArtwork") val frontArtwork: Boolean = false,
    @SerialName("OtherArtwork") val otherArtwork: String = "",
    @SerialName("GroupArtwork") val groupArtwork: Boolean = false,
)

@Serializable
data class ApiArtistsView(
    @SerialName("Artists") val artists: List<ApiArtist>? = null,
)

@Serializable
data class ApiArtistView(
    @SerialName("Artist") val artist: ApiArtist = ApiArtist(),
    @SerialName("Image") val image: String = "",
    @SerialName("Background") val background: String = "",
    @SerialName("Releases") val releases: List<ApiRelease>? = null,
    @SerialName("Similar") val similar: List<ApiArtist>? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)

/** /api/artists/{id}/popular */
@Serializable
data class ApiPopularView(
    @SerialName("Popular") val popular: List<ApiTrack>? = null,
)

@Serializable
data class ApiReleaseView(
    @SerialName("Artist") val artist: ApiArtist = ApiArtist(),
    @SerialName("Release") val release: ApiRelease = ApiRelease(),
    @SerialName("Image") val image: String = "",
    @SerialName("Background") val background: String = "",
    @SerialName("Tracks") val tracks: List<ApiTrack>? = null,
    @SerialName("Similar") val similar: List<ApiRelease>? = null,
)

@Serializable
data class ApiStation(
    @SerialName("ID") val id: Int = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("Creator") val creator: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("Image") val image: String = "",
    @SerialName("Description") val description: String = "",
)

/** /api/radio: the user's stations, grouped by kind. */
@Serializable
data class ApiRadioView(
    @SerialName("Artist") val artist: List<ApiStation>? = null,
    @SerialName("Genre") val genre: List<ApiStation>? = null,
    @SerialName("Similar") val similar: List<ApiStation>? = null,
    @SerialName("Period") val period: List<ApiStation>? = null,
    @SerialName("Series") val series: List<ApiStation>? = null,
    @SerialName("Other") val other: List<ApiStation>? = null,
    @SerialName("Stream") val stream: List<ApiStation>? = null,
)

@Serializable
data class ApiPlaylistSummary(
    @SerialName("ID") val id: Int = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("TrackCount") val trackCount: Int = 0,
)

@Serializable
data class ApiPlaylistsView(
    @SerialName("Playlists") val playlists: List<ApiPlaylistSummary>? = null,
)

// Spiff playlists, which every "play this" endpoint returns. Lower-case names, unlike the rest.

@Serializable
data class ApiPlaylist(
    @SerialName("playlist") val playlist: ApiSpiff = ApiSpiff(),
    @SerialName("index") val index: Int = 0,
    @SerialName("position") val position: Double = 0.0,
    @SerialName("type") val type: String = "",
)

@Serializable
data class ApiSpiff(
    @SerialName("title") val title: String = "",
    @SerialName("creator") val creator: String = "",
    @SerialName("image") val image: String = "",
    @SerialName("location") val location: String = "",
    @SerialName("track") val tracks: List<ApiSpiffEntry>? = null,
)

@Serializable
data class ApiSpiffEntry(
    @SerialName("creator") val creator: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("image") val image: String = "",
    /** The artist's background image (server path), or "". */
    @SerialName("background") val background: String = "",
    /** Stream locations; the first is used ("/api/tracks/{uuid}/location", or a full URL). */
    @SerialName("location") val location: List<String>? = null,
    /** The track's ETag first, when it has one (radio streams don't). */
    @SerialName("identifier") val identifier: List<String>? = null,
)
