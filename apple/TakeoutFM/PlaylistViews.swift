import SwiftUI
import Shared

/** The user's playlists, A–Z. */
struct PlaylistList: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Loaded(load: { try await model.calls.playlists() }) { playlists in
            if playlists.isEmpty {
                ContentUnavailableView("No Playlists", systemImage: "music.note.list")
            } else {
                List(playlists, id: \.id) { playlist in
                    NavigationLink(value: Route.playlist(id: playlist.id, name: playlist.name)) {
                        HStack {
                            Text(playlist.name)
                            Spacer()
                            Text("\(playlist.trackCount) tracks").foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Playlists")
    }
}

/** A playlist's tracks, from its play queue; double-click plays from there. */
struct PlaylistView: View {
    let playlistId: String
    let name: String
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var player: AudioPlayer

    var body: some View {
        Loaded(load: { try await model.calls.playlistQueue(playlistId: playlistId) }) { queue in
            List {
                HStack {
                    Button { player.play(queue, from: 0) } label: { Label("Play", systemImage: "play.fill") }
                        .buttonStyle(.borderedProminent)
                    Button { player.play(queue, shuffled: true) } label: { Label("Shuffle", systemImage: "shuffle") }
                }
                .disabled(queue.items.isEmpty)
                .padding(.vertical, 8)
                ForEach(Array(queue.items.enumerated()), id: \.offset) { index, item in
                    TrackRow(
                        number: "\(index + 1)",
                        title: item.title,
                        detail: "\(item.artist) · \(item.album)",
                        imageUri: item.imageUri
                    ) { player.play(queue, from: index) }
                }
            }
        }
        .navigationTitle(name)
    }
}

/** Favorite artists and tracks. */
struct FavoritesView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Loaded(load: { try await model.calls.favorites() }) { favorites in
            if favorites.artists.isEmpty && favorites.tracks.isEmpty {
                ContentUnavailableView("No Favorites", systemImage: "heart")
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 28) {
                        ArtistRow(title: "Artists", artists: favorites.artists)
                        if !favorites.tracks.isEmpty {
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    SectionTitle("Tracks")
                                    Spacer()
                                    Button { play(0) } label: { Label("Play All", systemImage: "play.fill") }
                                }
                                ForEach(Array(favorites.tracks.enumerated()), id: \.offset) { index, track in
                                    TrackRow(
                                        title: track.title,
                                        detail: "\(track.artist) · \(track.album)",
                                        imageUri: track.coverUri
                                    ) { play(index) }
                                }
                            }
                        }
                    }
                    .padding(24)
                }
            }
        }
        .navigationTitle("Favorites")
    }

    private func play(_ index: Int) {
        model.play { try await model.calls.favoritesQueue(startIndex: Int32(index)) }
    }
}
