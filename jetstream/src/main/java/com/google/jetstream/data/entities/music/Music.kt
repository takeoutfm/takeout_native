package com.google.jetstream.data.entities.music

// Music entities for the screens. Ids are prefixed by kind, like video's "m-"/"s-"/"e-":
// artists "ar-<id>", albums (releases) "al-<id>", tracks "t-<id>". Image URIs are full URLs,
// or "" when the server has none.

data class Artist(
    val id: String,
    val name: String,
    val imageUri: String,
    val genre: String = "",
)

data class Album(
    val id: String,
    val name: String,
    val artist: String,
    /** "1969", or "" when unknown. */
    val year: String,
    val coverUri: String,
    /** MusicBrainz type, e.g. "Album", "Single", "EP". */
    val type: String = "",
)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val trackNum: Int,
    val discNum: Int,
    val coverUri: String,
)

data class ArtistDetails(
    val artist: Artist,
    val backgroundUri: String,
    /** Studio albums, newest first. */
    val albums: List<Album>,
    /** Singles and EPs, newest first. */
    val singles: List<Album>,
    /** Live albums, compilations and the rest, newest first. */
    val otherReleases: List<Album>,
    val popular: List<Track>,
    val similar: List<Artist>,
    val isFavorite: Boolean,
)

data class AlbumDetails(
    val album: Album,
    val artistId: String,
    val backgroundUri: String,
    /** In disc and track order. */
    val tracks: List<Track>,
    val discCount: Int,
    val similar: List<Album>,
)

/** One playable entry of a queue, from a spiff playlist. */
data class QueueItem(
    val title: String,
    val artist: String,
    val album: String,
    val imageUri: String,
    /** The artist's background image, or "". */
    val backgroundUri: String = "",
    /** Full stream URL. */
    val uri: String,
    /** The track's ETag; "" for radio streams. */
    val etag: String,
)

/** What a "play this" request returns: a titled list of tracks and where to start. */
data class MusicQueue(
    val title: String,
    val creator: String,
    val imageUri: String,
    val items: List<QueueItem>,
    val startIndex: Int = 0,
)
