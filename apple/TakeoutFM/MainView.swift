import SwiftUI
import Shared

/** The main window: a sidebar of pages, the page (with its links), and the Now Playing bar. */
struct MainView: View {
    @EnvironmentObject private var model: AppModel

    enum Page: Hashable {
        case recentlyAdded, newReleases, recentlyPlayed, artists, favorites, playlists, radio, podcasts
        case watch, movies, shows, downloads
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
    @State private var lastSearch = ""
    @Environment(\.openWindow) private var openWindow

    private var musicPages: [Page] {
        var pages: [Page] = [.recentlyPlayed, .recentlyAdded, .newReleases, .artists, .favorites]
        if model.hasPlaylists { pages.append(.playlists) }
        pages.append(.radio)
        if model.hasPodcasts { pages.append(.podcasts) }
        return pages
    }

    private var videoPages: [Page] {
        guard model.hasMovies || model.hasShows else { return [] }
        var pages: [Page] = [.watch]
        if model.hasMovies { pages.append(.movies) }
        if model.hasShows { pages.append(.shows) }
        return pages
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
                if !lastSearch.isEmpty {
                    Section("Search") {
                        Label(lastSearch, systemImage: Page.search(lastSearch).icon).tag(Page.search(lastSearch))
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200)
            .toolbar {
                ToolbarItem {
                    Menu {
                        Button("Sign Out") { model.signOut() }
                    } label: {
                        Image(systemName: "person.crop.circle")
                    }
                }
            }
        } detail: {
            NavigationStack(path: $path) {
                pageView(page ?? .recentlyPlayed)
                    .navigationDestination(for: Route.self, destination: routeView)
            }
        }
        .searchable(text: $query, placement: .toolbar, prompt: "Artists, albums, tracks")
        .onSubmit(of: .search) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty else { return }
            lastSearch = q
            page = .search(q)
        }
        #if DEBUG
        // For testing video without the UI: `TakeoutFM -playVideo e-123` (add `-noProgress YES`).
        .onAppear {
            if let id = UserDefaults.standard.string(forKey: "playVideo") {
                openWindow(id: "video", value: VideoPlayRequest(id: id))
            }
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
        case .watch: WatchView()
        case .movies: PosterGrid(title: "Movies") { try await model.calls.movies() }
        case .shows: PosterGrid(title: "TV Shows") { try await model.calls.shows() }
        case .downloads: DownloadsView()
        case .search(let query): SearchView(query: query)
        }
    }

    @ViewBuilder
    private func routeView(_ route: Route) -> some View {
        switch route {
        case .album(let id): AlbumView(albumId: id)
        case .artist(let id): ArtistView(artistId: id)
        case .playlist(let id, let name): PlaylistView(playlistId: id, name: name)
        case .video(let id): VideoView(videoId: id)
        case .person(let id): PersonView(personId: id)
        case .podcast(let id): PodcastView(seriesId: id)
        case .podcastEpisode(let seriesId, let episodeId): PodcastEpisodeView(seriesId: seriesId, episodeId: episodeId)
        }
    }
}
