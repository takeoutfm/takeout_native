import SwiftUI
import Shared

/** A grid of album covers; selecting one opens its page. */
struct AlbumGrid: View {
    let title: String
    let load: @MainActor () async throws -> [Album]

    var body: some View {
        Loaded(load: load) { albums in
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 20)], spacing: 24) {
                    ForEach(albums, id: \.id) { album in
                        NavigationLink(value: Route.album(album.id)) {
                            AlbumCard(album: album)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(20)
            }
        }
        .navigationTitle(title)
    }
}

struct AlbumCard: View {
    let album: Album

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Artwork(url: album.coverUri, symbol: "music.note")
                .aspectRatio(1, contentMode: .fit)
            Text(album.name).font(.headline).lineLimit(1)
            Text(album.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

/** An album: cover, Play / Shuffle, and its tracks (double-click plays from there). */
struct AlbumView: View {
    let albumId: String
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var cache: MediaCache

    var body: some View {
        Loaded(load: { try await model.calls.albumDetails(albumId: albumId) }) { details in
            List {
                HeaderStack(alignment: .bottom) {
                    Artwork(url: details.album.coverUri, symbol: "music.note").frame(width: 200, height: 200)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(details.album.name).font(.largeTitle.bold())
                        NavigationLink(value: Route.artist(details.artistId)) {
                            Text(details.album.artist).font(.title2).foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        Text(details.album.year).foregroundStyle(.secondary)
                        FlowRow {
                            Button { play(0) } label: { Label("Play", systemImage: "play.fill") }
                                .buttonStyle(.borderedProminent)
                            Button { play(0, shuffled: true) } label: { Label("Shuffle", systemImage: "shuffle") }
                            DownloadButton(etags: details.tracks.map(\.etag)) {
                                Task {
                                    guard let queue = try? await model.calls.albumQueue(albumId: albumId, startIndex: 0) else { return }
                                    cache.download(queue)
                                }
                            }
                        }
                    }
                }
                .padding(.vertical, 12)
                ForEach(Array(details.tracks.enumerated()), id: \.offset) { index, track in
                    TrackRow(
                        number: "\(track.trackNum)",
                        title: track.title,
                        detail: track.artist != details.album.artist ? track.artist : "",
                        etag: track.etag,
                        enqueue: { model.enqueueTrack(track.id, last: $0) }
                    ) { play(index) }
                }
                AlbumRow(title: "Similar Albums", albums: details.similar)
                    .padding(.top, 16)
            }
            .navigationTitle(details.album.name)
        }
    }

    private func play(_ index: Int, shuffled: Bool = false) {
        model.play(shuffled: shuffled) { try await model.calls.albumQueue(albumId: albumId, startIndex: Int32(index)) }
    }
}
