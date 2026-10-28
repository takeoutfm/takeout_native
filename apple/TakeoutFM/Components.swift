import SwiftUI
import Shared

/** Where a page can link to; the main window's NavigationStack opens each. */
enum Route: Hashable {
    case album(String)
    case artist(String)
    case playlist(id: String, name: String)
    /** A movie, series or episode ("m-", "s-", "e-"). */
    case video(String)
    case person(String)
    /** A podcast ("ps-"). */
    case podcast(String)
    /** One of a podcast's episodes ("pe-"); its page loads the podcast for the neighbours. */
    case podcastEpisode(seriesId: String, episodeId: String)
}

/**
 * A page loaded by [load]: a spinner, "Couldn't load this" on failure, else [content].
 * Loads again when [id] changes (e.g. a new search).
 */
struct Loaded<Value, Content: View>: View {
    let id: AnyHashable
    let load: @MainActor () async throws -> Value
    let content: (Value) -> Content
    @State private var value: Value?
    @State private var failed = false

    init(
        id: AnyHashable = 0,
        load: @escaping @MainActor () async throws -> Value,
        @ViewBuilder content: @escaping (Value) -> Content
    ) {
        self.id = id
        self.load = load
        self.content = content
    }

    var body: some View {
        Group {
            if let value {
                content(value)
            } else if failed {
                ContentUnavailableView("Couldn't load this", systemImage: "wifi.exclamationmark")
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: id) {
            value = nil
            failed = false
            do {
                value = try await load()
            } catch is CancellationError {
            } catch {
                NSLog("Load failed: \(error)")
                failed = true
            }
        }
    }
}

/**
 * Remote artwork filling exactly the space it's given (cropped to it), or with [fit] the whole
 * image at its own shape (as tall as it is for the width given; a square placeholder until it
 * loads); a plain placeholder (with [symbol], if given) while loading or when there's none.
 */
struct Artwork: View {
    let url: String
    var symbol: String?
    var fit = false

    var body: some View {
        if fit {
            AsyncImage(url: URL(string: url)) { image in
                image.resizable().scaledToFit()
            } placeholder: {
                placeholder.aspectRatio(1, contentMode: .fit)
            }
            .clipShape(RoundedRectangle(cornerRadius: 6))
        } else {
            // The clear base takes the proposed size, so a wide or tall image can't spill out of it.
            Color.clear
                .overlay {
                    AsyncImage(url: URL(string: url)) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        placeholder
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
    }

    private var placeholder: some View {
        Rectangle().fill(.quaternary).overlay {
            if let symbol {
                Image(systemName: symbol).font(.title).foregroundStyle(.secondary)
            }
        }
    }
}

/** A track in a list; double-click (or Play in its menu) plays it. With an [etag], a ♥ button. */
struct TrackRow: View {
    var number: String?
    let title: String
    var detail = ""
    var imageUri: String?
    var etag = ""
    /// Play Next (false) / Add to Queue (true), when the row can be queued.
    var enqueue: ((Bool) -> Void)?
    let play: () -> Void
    @EnvironmentObject private var model: AppModel
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 12) {
            if let number {
                Text(number).monospacedDigit().foregroundStyle(.secondary).frame(width: 32, alignment: .trailing)
            }
            if let imageUri {
                Artwork(url: imageUri).frame(width: 36, height: 36)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                if !detail.isEmpty {
                    Text(detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if !etag.isEmpty {
                DownloadBadge(etag: etag)
                // Shown while it's a favorite, or under the pointer.
                FavoriteButton(etag: etag).opacity(model.isFavorite(etag) || hovering ? 1 : 0)
            }
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture(count: 2, perform: play)
        .contextMenu {
            Button("Play", action: play)
            if let enqueue {
                Button("Play Next") { enqueue(false) }
                Button("Add to Queue") { enqueue(true) }
            }
            if !etag.isEmpty {
                Divider()
                Button(model.isFavorite(etag) ? "Remove from Favorites" : "Add to Favorites") {
                    model.toggleFavorite(etag)
                }
            }
        }
    }
}

/** ♥ for a track (by ETag): filled when it's a favorite; clicking toggles it. */
struct FavoriteButton: View {
    let etag: String
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let favorite = model.isFavorite(etag)
        Button { model.toggleFavorite(etag) } label: {
            Image(systemName: favorite ? "heart.fill" : "heart")
        }
        .buttonStyle(.borderless)
        .help(favorite ? "Remove from Favorites" : "Add to Favorites")
    }
}

struct SectionTitle: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.title2.bold())
    }
}

/** A titled, horizontally scrolling row of albums; hidden when there are none. */
struct AlbumRow: View {
    let title: String
    let albums: [Album]

    var body: some View {
        if !albums.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 16) {
                        ForEach(albums, id: \.id) { album in
                            NavigationLink(value: Route.album(album.id)) {
                                AlbumCard(album: album).frame(width: 150)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
    }
}

/** A titled, horizontally scrolling row of artists; hidden when there are none. */
struct ArtistRow: View {
    let title: String
    let artists: [Artist]

    var body: some View {
        if !artists.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 16) {
                        ForEach(artists, id: \.id) { artist in
                            NavigationLink(value: Route.artist(artist.id)) {
                                ArtistCard(artist: artist).frame(width: 120)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
    }
}
