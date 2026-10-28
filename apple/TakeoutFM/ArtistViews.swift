import SwiftUI
import Shared

/** Every artist, A–Z. */
struct ArtistList: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Loaded(load: { try await model.calls.artists() }) { artists in
            List(artists, id: \.id) { artist in
                NavigationLink(value: Route.artist(artist.id)) {
                    HStack(spacing: 10) {
                        Artwork(url: artist.imageUri, symbol: "person.fill")
                            .frame(width: 28, height: 28)
                            .clipShape(Circle())
                        Text(artist.name)
                    }
                }
            }
        }
        .navigationTitle("Artists")
    }
}

struct ArtistCard: View {
    let artist: Artist

    var body: some View {
        VStack(spacing: 6) {
            Artwork(url: artist.imageUri, symbol: "person.fill")
                .aspectRatio(1, contentMode: .fit)
                .clipShape(Circle())
            Text(artist.name).font(.headline).lineLimit(1)
        }
    }
}

/** An artist: Shuffle / Radio mixes, popular tracks, releases by kind and similar artists. */
struct ArtistView: View {
    let artistId: String
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Loaded(load: { try await model.calls.artistDetails(artistId: artistId) }) { details in
            ArtistPage(artistId: artistId, details: details)
        }
    }
}

private struct ArtistPage: View {
    let artistId: String
    let details: ArtistDetails
    @EnvironmentObject private var model: AppModel
    @State private var favorite: Bool

    init(artistId: String, details: ArtistDetails) {
        self.artistId = artistId
        self.details = details
        _favorite = State(initialValue: details.isFavorite)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                HStack(alignment: .bottom, spacing: 20) {
                    Artwork(url: details.artist.imageUri, symbol: "person.fill")
                        .frame(width: 180, height: 180)
                        .clipShape(Circle())
                    VStack(alignment: .leading, spacing: 8) {
                        Text(details.artist.name).font(.largeTitle.bold())
                        if !details.artist.genre.isEmpty {
                            Text(details.artist.genre).foregroundStyle(.secondary)
                        }
                        HStack {
                            Button { mix(.shuffle) } label: { Label("Shuffle", systemImage: "shuffle") }
                                .buttonStyle(.borderedProminent)
                            Button { mix(.radio) } label: { Label("Radio", systemImage: "dot.radiowaves.left.and.right") }
                            if !details.artist.arid.isEmpty {
                                Button(action: toggleFavorite) {
                                    Image(systemName: favorite ? "heart.fill" : "heart")
                                }
                                .help(favorite ? "Remove from Favorites" : "Add to Favorites")
                            }
                        }
                    }
                }
                if !details.popular.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        SectionTitle("Popular")
                        ForEach(Array(details.popular.prefix(10).enumerated()), id: \.offset) { index, track in
                            TrackRow(title: track.title, detail: track.album, imageUri: track.coverUri, etag: track.etag) {
                                model.play { model.calls.tracksQueue(title: "Popular", tracks: details.popular, startIndex: Int32(index)) }
                            }
                        }
                    }
                }
                AlbumRow(title: "Albums", albums: details.albums)
                AlbumRow(title: "Singles & EPs", albums: details.singles)
                AlbumRow(title: "Other Releases", albums: details.otherReleases)
                ArtistRow(title: "Similar Artists", artists: details.similar)
            }
            .padding(24)
        }
        .navigationTitle(details.artist.name)
    }

    private func mix(_ mix: ArtistMix) {
        model.play { try await model.calls.artistQueue(artistId: artistId, mix: mix) }
    }

    private func toggleFavorite() {
        let wanted = !favorite
        favorite = wanted
        Task {
            do {
                try await model.calls.setArtistFavorite(arid: details.artist.arid, favorite: wanted)
            } catch {
                NSLog("Favorite failed: \(error)")
                favorite = !wanted
            }
        }
    }
}
