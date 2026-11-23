import SwiftUI
import Shared

/** [PlaylistView]'s id for the favorite tracks. */
let favoriteTracksId = "favorites"

/**
 * Favorite Tracks (as on the watch), then the user's playlists A–Z, as a grid of covers: each
 * playlist's art is a mosaic of its first four albums' covers. New Playlist in the toolbar;
 * a playlist's menu deletes it.
 */
struct PlaylistList: View {
    @EnvironmentObject private var model: AppModel
    @State private var naming = false
    @State private var newName = ""
    @State private var deleting: MusicPlaylist?

    /** The first load failed, with nothing to show. */
    @State private var failed = false

    var body: some View {
        Group {
            if let playlists = model.playlists {
                grid(playlists)
            } else if failed {
                ContentUnavailableView("Couldn't load this", systemImage: "wifi.exclamationmark")
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        // Each visit asks the server again (another app may have changed them); edits made here
        // show at once through model.playlists, the shared core's current list.
        .task {
            do {
                _ = try await model.calls.playlists()
                failed = false
            } catch {
                failed = model.playlists == nil
            }
        }
        .navigationTitle("Playlists")
        .toolbar {
            ToolbarItem {
                Button {
                    newName = ""
                    naming = true
                } label: {
                    Label("New Playlist", systemImage: "plus")
                }
                .help("New Playlist")
            }
        }
        .alert("New Playlist", isPresented: $naming) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Create") { create(newName) }
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .confirmDeletePlaylist($deleting) { delete($0) }
    }

    private func grid(_ playlists: [MusicPlaylist]) -> some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 20)], spacing: 24) {
                NavigationLink(value: Route.playlist(id: favoriteTracksId, name: "Favorite Tracks")) {
                    VStack(alignment: .leading, spacing: 6) {
                        FavoriteTracksArt().aspectRatio(1, contentMode: .fit)
                        Text("Favorite Tracks").font(.headline).lineLimit(1)
                        Text("Tracks you ♥").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
                ForEach(playlists, id: \.id) { playlist in
                    NavigationLink(value: Route.playlist(id: playlist.id, name: playlist.name)) {
                        PlaylistCard(playlist: playlist)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button("Delete Playlist…", systemImage: "trash", role: .destructive) { deleting = playlist }
                    }
                }
            }
            .padding(20)
        }
    }

    private func create(_ name: String) {
        let name = name.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        Task {
            do {
                _ = try await model.calls.createPlaylist(name: name)
            } catch {
                model.show("Couldn't create \(name)")
            }
        }
    }

    private func delete(_ playlist: MusicPlaylist) {
        Task {
            do {
                try await model.calls.deletePlaylist(playlistId: playlist.id)
            } catch {
                model.show("Couldn't delete \(playlist.name)")
            }
        }
    }
}

extension View {
    /** Asks before deleting [playlist] (set to ask), then [delete]s it. */
    func confirmDeletePlaylist(_ playlist: Binding<MusicPlaylist?>, delete: @escaping (MusicPlaylist) -> Void) -> some View {
        confirmationDialog(
            "Delete “\((playlist.wrappedValue?.name ?? "").trimmingCharacters(in: .whitespaces))”?",
            isPresented: Binding(get: { playlist.wrappedValue != nil }, set: { if !$0 { playlist.wrappedValue = nil } }),
            titleVisibility: .visible,
            presenting: playlist.wrappedValue
        ) { p in
            Button("Delete", role: .destructive) { delete(p) }
        } message: { _ in
            Text("The playlist is deleted on the server, for every app. Its tracks stay in the library.")
        }
    }
}

/** "1 track", "12 tracks". */
func trackCount(_ n: Int) -> String { n == 1 ? "1 track" : "\(n) tracks" }

/** A playlist's mosaic, name and track count; the mosaic loads as the card comes into view. */
private struct PlaylistCard: View {
    let playlist: MusicPlaylist
    @EnvironmentObject private var model: AppModel
    @State private var covers: [String]?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            PlaylistMosaic(covers: covers ?? []).aspectRatio(1, contentMode: .fit)
            Text(playlist.name).font(.headline).lineLimit(1)
            Text(trackCount(Int(playlist.trackCount))).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
        }
        .task(id: playlist.trackCount) {
            let count = Int(playlist.trackCount)
            if let kept = PlaylistCovers.kept(playlist.id, count: count) {
                covers = kept
                return
            }
            guard count > 0, let queue = try? await model.calls.playlistQueue(playlistId: playlist.id) else { return }
            covers = PlaylistCovers.keep(queue, for: playlist.id, count: count)
        }
    }
}

/**
 * Each playlist's covers, kept while the app runs so the grid doesn't fetch every playlist on
 * each visit; fetched again when its track count changes (a track added or removed).
 */
@MainActor
enum PlaylistCovers {
    private static var kept: [String: (count: Int, covers: [String])] = [:]

    static func kept(_ id: String, count: Int) -> [String]? {
        guard let k = kept[id], k.count == count else { return nil }
        return k.covers
    }

    /** The first four different album covers in [queue], kept for playlist [id]. */
    static func keep(_ queue: MusicQueue, for id: String, count: Int) -> [String] {
        var covers: [String] = []
        for item in queue.items where !item.imageUri.isEmpty && !covers.contains(item.imageUri) {
            covers.append(item.imageUri)
            if covers.count == 4 { break }
        }
        kept[id] = (count, covers)
        return covers
    }
}

/** Four covers in a square, or the first alone when there are fewer; a placeholder with none. */
struct PlaylistMosaic: View {
    let covers: [String]

    var body: some View {
        Group {
            if covers.count >= 4 {
                VStack(spacing: 0) {
                    HStack(spacing: 0) { tile(covers[0]); tile(covers[1]) }
                    HStack(spacing: 0) { tile(covers[2]); tile(covers[3]) }
                }
            } else if let first = covers.first {
                tile(first)
            } else {
                Rectangle().fill(.quaternary).overlay {
                    Image(systemName: "music.note.list").font(.largeTitle).foregroundStyle(.secondary)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    /** One cover, square, filling its share (no rounded corners of its own inside the square). */
    private func tile(_ url: String) -> some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                AsyncImage(url: URL(string: url)) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Rectangle().fill(.quaternary)
                }
            }
            .clipped()
    }
}

/** Favorite Tracks' art: a heart on a warm gradient, set apart from the playlists' covers. */
struct FavoriteTracksArt: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 6)
            .fill(LinearGradient(colors: [.pink, .red], startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay {
                Image(systemName: "heart.fill").font(.system(size: 56)).foregroundStyle(.white)
            }
    }
}

/**
 * A playlist's (or the favorite tracks') page: its mosaic beside the name, track count and
 * Play / Shuffle / Download, as an album's; then the tracks (double-click plays from there).
 * A playlist's menu deletes it.
 */
struct PlaylistView: View {
    let playlistId: String
    let name: String
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var cache: MediaCache
    @Environment(\.dismiss) private var dismiss
    @State private var deleting: MusicPlaylist?

    private var isFavorites: Bool { playlistId == favoriteTracksId }

    var body: some View {
        Loaded(load: {
            if isFavorites { return try await model.calls.favoritesQueue(startIndex: 0) }
            return try await model.calls.playlistQueue(playlistId: playlistId)
        }) { queue in
            List {
                HeaderStack(alignment: .bottom) {
                    Group {
                        if isFavorites {
                            FavoriteTracksArt()
                        } else {
                            PlaylistMosaic(covers: PlaylistCovers.keep(queue, for: playlistId, count: queue.items.count))
                        }
                    }
                    .frame(width: 200, height: 200)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(name).font(.largeTitle.bold())
                        Text(trackCount(queue.items.count)).font(.title3).foregroundStyle(.secondary)
                        FlowRow {
                            Button { player.play(queue, from: 0) } label: { Label("Play", systemImage: "play.fill") }
                                .buttonStyle(.borderedProminent)
                            Button { player.play(queue, shuffled: true) } label: { Label("Shuffle", systemImage: "shuffle") }
                            DownloadButton(etags: queue.items.map(\.etag)) {
                                cache.download(queue)
                            }
                        }
                        .buttonStyle(.bordered)
                        // In a List row a Label takes the row's look: a wide gap and an accent icon, unseen on Play.
                        .labelStyle(.titleAndIcon)
                        .disabled(queue.items.isEmpty)
                    }
                }
                .padding(.vertical, 12)
                ForEach(Array(queue.items.enumerated()), id: \.offset) { index, item in
                    TrackRow(
                        number: "\(index + 1)",
                        title: item.title,
                        detail: "\(item.artist) · \(item.album)",
                        imageUri: item.imageUri,
                        etag: item.etag,
                        enqueue: { model.enqueue([item], last: $0) }
                    ) { player.play(queue, from: index) }
                }
            }
        }
        .navigationTitle(name)
        .toolbar {
            if !isFavorites {
                ToolbarItem {
                    Menu {
                        Button("Delete Playlist…", systemImage: "trash", role: .destructive) {
                            deleting = MusicPlaylist(id: playlistId, name: name, trackCount: 0)
                        }
                    } label: {
                        Label("More", systemImage: "ellipsis")
                    }
                    .help("More")
                }
            }
        }
        .confirmDeletePlaylist($deleting) { playlist in
            Task {
                do {
                    try await model.calls.deletePlaylist(playlistId: playlist.id)
                    dismiss()
                } catch {
                    model.show("Couldn't delete \(playlist.name)")
                }
            }
        }
    }
}

/** Favorite artists and tracks. */
struct FavoritesView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var cache: MediaCache

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
                                    DownloadButton(etags: favorites.tracks.map(\.etag)) {
                                        Task {
                                            guard let queue = try? await model.calls.favoritesQueue(startIndex: 0) else { return }
                                            cache.download(queue)
                                        }
                                    }
                                }
                                .buttonStyle(.bordered)
                                ForEach(Array(favorites.tracks.enumerated()), id: \.offset) { index, track in
                                    TrackRow(
                                        title: track.title,
                                        detail: "\(track.artist) · \(track.album)",
                                        imageUri: track.coverUri,
                                        etag: track.etag,
                                        enqueue: { model.enqueueTrack(track.id, last: $0) }
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
