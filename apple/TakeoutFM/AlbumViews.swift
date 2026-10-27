import SwiftUI
import Shared

/** A grid of album covers; selecting one opens its page. */
struct AlbumGrid: View {
    let title: String
    let load: () async throws -> [Album]
    @State private var albums: [Album]?
    @State private var failed = false

    var body: some View {
        Group {
            if let albums {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 20)], spacing: 24) {
                        ForEach(albums, id: \.id) { album in
                            NavigationLink(value: album.id) {
                                AlbumCard(album: album)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(20)
                }
            } else if failed {
                ContentUnavailableView("Couldn't load this", systemImage: "wifi.exclamationmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(title)
        .navigationDestination(for: String.self) { AlbumView(albumId: $0) }
        .task {
            do { albums = try await load() } catch { failed = true }
        }
    }
}

struct AlbumCard: View {
    let album: Album

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Artwork(url: album.coverUri)
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
    @EnvironmentObject private var player: AudioPlayer
    @State private var details: AlbumDetails?
    @State private var failed = false

    var body: some View {
        Group {
            if let details {
                List {
                    HStack(alignment: .bottom, spacing: 20) {
                        Artwork(url: details.album.coverUri).frame(width: 200, height: 200)
                        VStack(alignment: .leading, spacing: 8) {
                            Text(details.album.name).font(.largeTitle.bold())
                            Text(details.album.artist).font(.title2).foregroundStyle(.secondary)
                            Text(details.album.year).foregroundStyle(.secondary)
                            HStack {
                                Button { play(0) } label: { Label("Play", systemImage: "play.fill") }
                                    .buttonStyle(.borderedProminent)
                                Button { play(0, shuffled: true) } label: { Label("Shuffle", systemImage: "shuffle") }
                            }
                        }
                    }
                    .padding(.vertical, 12)
                    ForEach(Array(details.tracks.enumerated()), id: \.offset) { index, track in
                        HStack {
                            Text("\(track.trackNum)").monospacedDigit().foregroundStyle(.secondary).frame(width: 32, alignment: .trailing)
                            Text(track.title)
                            Spacer()
                            if track.artist != details.album.artist {
                                Text(track.artist).foregroundStyle(.secondary)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture(count: 2) { play(index) }
                    }
                }
            } else if failed {
                ContentUnavailableView("Couldn't load this", systemImage: "wifi.exclamationmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(details?.album.name ?? "")
        .task {
            do { details = try await model.calls.albumDetails(albumId: albumId) } catch { failed = true }
        }
    }

    private func play(_ index: Int, shuffled: Bool = false) {
        Task {
            guard let queue = try? await model.calls.albumQueue(albumId: albumId, startIndex: Int32(index)) else { return }
            player.play(queue, shuffled: shuffled)
        }
    }
}

/** Remote artwork with a plain placeholder. */
struct Artwork: View {
    let url: String

    var body: some View {
        AsyncImage(url: URL(string: url)) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            Rectangle().fill(.quaternary)
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
