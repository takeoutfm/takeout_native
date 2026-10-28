import SwiftUI
import Shared

private struct CompactLayoutKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /** A narrow screen (iPhone): page headers stack their art above the details. */
    var compactLayout: Bool {
        get { self[CompactLayoutKey.self] }
        set { self[CompactLayoutKey.self] = newValue }
    }
}

/**
 * A page's action buttons: each at its natural size, wrapping to the next line when they don't
 * fit (on a phone, "Resume from 8:27 · Start Over · Download · ♥" squeezed into one row and
 * wrapped their labels instead).
 */
struct FlowRow: Layout {
    var spacing: CGFloat = 10
    var lineSpacing: CGFloat = 10

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        let height = rows.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(rows.count - 1, 0))
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width.map { min($0, width) } ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for (index, size) in row.items {
                // Centred in the row's height, so a big Play and small icons line up.
                subviews[index].place(at: CGPoint(x: x, y: y + (row.height - size.height) / 2),
                                      proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Row {
        var items: [(Int, CGSize)] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for (index, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(.unspecified)
            let needed = row.items.isEmpty ? size.width : row.width + spacing + size.width
            if !row.items.isEmpty && needed > width {
                rows.append(row)
                row = Row()
            }
            row.width = row.items.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.items.append((index, size))
        }
        if !row.items.isEmpty { rows.append(row) }
        return rows
    }
}

/** A page header: the art beside the details, or above them on a narrow screen. */
struct HeaderStack<Content: View>: View {
    var alignment: VerticalAlignment = .bottom
    @ViewBuilder let content: () -> Content
    @Environment(\.compactLayout) private var compact

    var body: some View {
        if compact {
            VStack(alignment: .leading, spacing: 16, content: content)
        } else {
            HStack(alignment: alignment, spacing: 20, content: content)
        }
    }
}

/** The page a [Route] opens, in any NavigationStack (the Mac's and iPad's sidebar, the iPhone's tabs). */
struct RouteView: View {
    let route: Route

    var body: some View {
        switch route {
        case .album(let id): AlbumView(albumId: id)
        case .artist(let id): ArtistView(artistId: id)
        case .playlist(let id, let name): PlaylistView(playlistId: id, name: name)
        case .video(let id): VideoView(videoId: id)
        case .person(let id): PersonView(personId: id)
        case .podcast(let id): PodcastView(seriesId: id)
        case .podcastEpisode(let seriesId, let episodeId): PodcastEpisodeView(seriesId: seriesId, episodeId: episodeId)
        case .list(let list): ListPage(list: list)
        }
    }
}

private struct ListPage: View {
    let list: LibraryList
    @EnvironmentObject private var model: AppModel

    var body: some View {
        switch list {
        case .recentlyAdded: AlbumGrid(title: "Recently Added") { try await model.calls.recentlyAdded() }
        case .newReleases: AlbumGrid(title: "New Releases") { try await model.calls.doNewReleases() }  // "do": Kotlin's prefix for "new…"
        case .favorites: FavoritesView()
        case .playlists: PlaylistList()
        case .podcasts: PodcastsView()
        case .watch: WatchView()
        case .movies: PosterGrid(title: "Movies") { try await model.calls.movies() }
        case .shows: PosterGrid(title: "TV Shows") { try await model.calls.shows() }
        case .downloads: DownloadsView()
        }
    }
}

/**
 * Plays a movie or episode: the Mac opens its video window, iOS presents the player full
 * screen. Each app's root view provides it.
 */
struct PlayVideoAction {
    let run: (VideoPlayRequest) -> Void
    func callAsFunction(_ request: VideoPlayRequest) { run(request) }
}

private struct PlayVideoKey: EnvironmentKey {
    static let defaultValue = PlayVideoAction { _ in }
}

extension EnvironmentValues {
    var playVideo: PlayVideoAction {
        get { self[PlayVideoKey.self] }
        set { self[PlayVideoKey.self] = newValue }
    }
}

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
    /** A whole list page, for the iPhone's Library tab (the sidebar shows these directly). */
    case list(LibraryList)
}

enum LibraryList: Hashable {
    case recentlyAdded, newReleases, favorites, playlists, podcasts, watch, movies, shows, downloads
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

/** A track in a list; double-click (a tap on iOS), or Play in its menu, plays it. With an [etag], a ♥ button. */
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
        #if os(macOS)
        .onTapGesture(count: 2, perform: play)
        #else
        .onTapGesture(perform: play)
        #endif
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
