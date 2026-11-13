import SwiftUI
import Shared

/** The main window: a sidebar of pages, the page (with its links), and the Now Playing bar. */
struct MainView: View {
    @EnvironmentObject private var model: AppModel

    enum Page: Hashable {
        case recentlyAdded, newReleases, recentlyPlayed, artists, favorites, playlists, radio, podcasts
        case live, watch, movies, shows, downloads
        case search(String)

        var title: String {
            switch self {
            case .recentlyAdded: return "Recently Added"
            case .newReleases: return "New Releases"
            case .recentlyPlayed: return "Recently Played"
            case .artists: return "Artists"
            case .favorites: return "Favorites"
            case .playlists: return "Playlists"
            case .radio: return "Radio"
            case .podcasts: return "Podcasts"
            case .live: return "Live"
            case .watch: return "Watch"
            case .movies: return "Movies"
            case .shows: return "TV Shows"
            case .downloads: return "Downloads"
            case .search(let query): return query
            }
        }

        var icon: String {
            switch self {
            case .recentlyAdded: return "plus.square.on.square"
            case .newReleases: return "sparkles"
            case .recentlyPlayed: return "clock.arrow.circlepath"
            case .artists: return "music.mic"
            case .favorites: return "heart"
            case .playlists: return "music.note.list"
            case .radio: return "dot.radiowaves.left.and.right"
            case .podcasts: return "antenna.radiowaves.left.and.right"
            case .live: return "dot.radiowaves.up.forward"
            case .watch: return "play.tv"
            case .movies: return "film"
            case .shows: return "tv"
            case .downloads: return "arrow.down.circle"
            case .search: return "magnifyingglass"
            }
        }
    }

    @State private var page: Page? = .recentlyPlayed
    @State private var path: [Route] = []
    @State private var query = ""
    @State private var showAccount = false
    @Environment(\.playVideo) private var playVideo
    @Environment(\.playLive) private var playLive

    private var musicPages: [Page] {
        var pages: [Page] = [.recentlyPlayed, .recentlyAdded, .newReleases, .artists, .favorites]
        if model.hasPlaylists { pages.append(.playlists) }
        pages.append(.radio)
        if model.hasPodcasts { pages.append(.podcasts) }
        return pages
    }

    private var videoPages: [Page] {
        var pages: [Page] = model.hasGuide ? [.live] : []
        guard model.hasMovies || model.hasShows else { return pages }
        pages.append(.watch)
        if model.hasMovies { pages.append(.movies) }
        if model.hasShows { pages.append(.shows) }
        return pages
    }

    /// A label over its value, in the account popover.
    private func accountRow(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value)
        }
    }

    var body: some View {
        NavigationSplitView {
            List(selection: $page) {
                Section("Music") {
                    ForEach(musicPages, id: \.self) { p in
                        Label(p.title, systemImage: p.icon).tag(p)
                    }
                }
                if !videoPages.isEmpty {
                    Section("Video") {
                        ForEach(videoPages, id: \.self) { p in
                            Label(p.title, systemImage: p.icon).tag(p)
                        }
                    }
                }
                Section("On This Mac") {
                    Label(Page.downloads.title, systemImage: Page.downloads.icon).tag(Page.downloads)
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200)
            .toolbar {
                ToolbarItem {
                    // Who's signed in, and where (the user once /api/me has said). A popover,
                    // not a menu, which would grey out text that isn't a command.
                    Button {
                        showAccount.toggle()
                    } label: {
                        Image(systemName: "person.crop.circle")
                    }
                    .help(model.serverUser.map { "Connected as \($0)" } ?? "Account")
                    .popover(isPresented: $showAccount, arrowEdge: .bottom) {
                        // As Linux's Account card: the server, then who's signed in to it.
                        VStack(alignment: .leading, spacing: 2) {
                            accountRow("Server", model.endpoint)
                            if let user = model.serverUser {
                                accountRow("Connected as", user).padding(.top, 8)
                            }
                            Divider().padding(.vertical, 8)
                            Button("Sign Out") {
                                showAccount = false
                                model.signOut()
                            }
                        }
                        .padding()
                        .frame(minWidth: 200, alignment: .leading)
                    }
                }
            }
        } detail: {
            NavigationStack(path: $path) {
                pageView(page ?? .recentlyPlayed)
                    .navigationDestination(for: Route.self) { RouteView(route: $0) }
            }
        }
        .searchable(text: $query, placement: .toolbar, prompt: "Artists, albums, tracks")
        .onSubmit(of: .search) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty else { return }
            page = .search(q)
        }
        #if DEBUG
        // For testing video without the UI: `TakeoutFM -playVideo e-123` (add `-noProgress YES`).
        .onAppear {
            if let id = UserDefaults.standard.string(forKey: "playVideo") {
                playVideo(VideoPlayRequest(id: id))
            }
            // Live: `-livePage YES` opens the page (`-liveGuideGrid YES` for the grid, which
            // holds the On Now / Guide switch there while the app runs), `-playLive "Channel"`
            // plays a channel.
            if UserDefaults.standard.bool(forKey: "livePage") { page = .live }
            if let channel = UserDefaults.standard.string(forKey: "playLive") { playLive(channel) }
        }
        #endif
        .onChange(of: page) {
            // After the sidebar's selection callback: changing the stack inside it re-enters
            // the sidebar's table (AppKit warns, and says it will assert).
            guard !path.isEmpty else { return }
            DispatchQueue.main.async { path = [] }
        }
        .safeAreaInset(edge: .bottom) {
            if !model.showNowPlaying { NowPlayingBar() }
        }
        .overlay {
            if model.showNowPlaying {
                NowPlayingView().transition(.move(edge: .bottom))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.showNowPlaying)
        .overlay(alignment: .bottom) {
            if let message = model.message {
                Text(message)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.bottom, 84)
                    .transition(.opacity)
            }
        }
        .animation(.default, value: model.message)
    }

    @ViewBuilder
    private func pageView(_ page: Page) -> some View {
        switch page {
        case .recentlyAdded:
            AlbumGrid(title: "Recently Added") { try await model.calls.recentlyAdded() }
        case .newReleases:
            AlbumGrid(title: "New Releases") { try await model.calls.doNewReleases() }  // Kotlin adds "do" to names starting with "new"
        case .recentlyPlayed: RecentlyPlayedView()
        case .artists: ArtistList()
        case .favorites: FavoritesView()
        case .playlists: PlaylistList()
        case .radio: RadioView()
        case .podcasts: PodcastsView()
        case .live: LiveView()
        case .watch: WatchView()
        case .movies: PosterGrid(title: "Movies") { try await model.calls.movies() }
        case .shows: PosterGrid(title: "TV Shows") { try await model.calls.shows() }
        case .downloads: DownloadsView()
        case .search(let query): SearchView(query: query)
        }
    }
}
