import SwiftUI
import Shared

/** Results for a query typed in the window's search field (the server's syntax, e.g. "artist:Beck"). */
struct SearchView: View {
    let query: String
    @EnvironmentObject private var model: AppModel

    private struct Results {
        let music: MusicSearchResults
        let video: VideoSearchResults?
        var isEmpty: Bool {
            music.isEmpty && (video.map { $0.movies.isEmpty && $0.shows.isEmpty && $0.episodes.isEmpty } ?? true)
        }
    }

    var body: some View {
        Loaded(id: query, load: {
            // Video search is extra: a failure there leaves the music results.
            async let video = try? await model.calls.videoSearch(query: query)
            let music = try await model.calls.search(query: query)
            return Results(music: music, video: await video)
        }) { found in
            let results = found.music
            if found.isEmpty {
                ContentUnavailableView.search(text: query)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 28) {
                        ArtistRow(title: "Artists", artists: results.artists)
                        AlbumRow(title: "Albums", albums: results.albums)
                        StationRow(title: "Radio", stations: results.stations)
                        if let video = found.video {
                            VideoRow(title: "Movies", cards: video.movies)
                            VideoRow(title: "TV Shows", cards: video.shows)
                            VideoRow(title: "Episodes", cards: video.episodes, wide: true)
                        }
                        if !results.tracks.isEmpty {
                            VStack(alignment: .leading, spacing: 6) {
                                SectionTitle("Tracks")
                                ForEach(Array(results.tracks.enumerated()), id: \.offset) { index, track in
                                    TrackRow(
                                        title: track.title,
                                        detail: "\(track.artist) · \(track.album)",
                                        imageUri: track.coverUri,
                                        etag: track.etag,
                                        enqueue: { model.enqueueTrack(track.id, last: $0) }
                                    ) {
                                        model.play { model.calls.tracksQueue(title: query, tracks: results.tracks, startIndex: Int32(index)) }
                                    }
                                }
                            }
                        }
                    }
                    .padding(24)
                }
            }
        }
        .navigationTitle("Search: \(query)")
    }
}
