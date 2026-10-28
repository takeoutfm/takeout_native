import SwiftUI
import Shared

/** Results for a query typed in the window's search field (the server's syntax, e.g. "artist:Beck"). */
struct SearchView: View {
    let query: String
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Loaded(id: query, load: { try await model.calls.search(query: query) }) { results in
            if results.isEmpty {
                ContentUnavailableView.search(text: query)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 28) {
                        ArtistRow(title: "Artists", artists: results.artists)
                        AlbumRow(title: "Albums", albums: results.albums)
                        StationRow(title: "Radio", stations: results.stations)
                        if !results.tracks.isEmpty {
                            VStack(alignment: .leading, spacing: 6) {
                                SectionTitle("Tracks")
                                ForEach(Array(results.tracks.enumerated()), id: \.offset) { index, track in
                                    TrackRow(
                                        title: track.title,
                                        detail: "\(track.artist) · \(track.album)",
                                        imageUri: track.coverUri
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
