import SwiftUI
import Shared

/** The main window: a sidebar of pages, the page (with its links), and the Now Playing bar. */
struct MainView: View {
    @EnvironmentObject private var model: AppModel

    enum Page: Hashable {
        case recentlyAdded, newReleases, artists, favorites, playlists, radio
        case search(String)

        var title: String {
            switch self {
            case .recentlyAdded: return "Recently Added"
            case .newReleases: return "New Releases"
            case .artists: return "Artists"
            case .favorites: return "Favorites"
            case .playlists: return "Playlists"
            case .radio: return "Radio"
            case .search(let query): return query
            }
        }

        var icon: String {
            switch self {
            case .recentlyAdded: return "plus.square.on.square"
            case .newReleases: return "sparkles"
            case .artists: return "music.mic"
            case .favorites: return "heart"
            case .playlists: return "music.note.list"
            case .radio: return "dot.radiowaves.left.and.right"
            case .search: return "magnifyingglass"
            }
        }
    }

    @State private var page: Page? = .recentlyAdded
    @State private var path: [Route] = []
    @State private var query = ""
    @State private var lastSearch = ""

    private var musicPages: [Page] {
        var pages: [Page] = [.recentlyAdded, .newReleases, .artists, .favorites]
        if model.hasPlaylists { pages.append(.playlists) }
        pages.append(.radio)
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
                pageView(page ?? .recentlyAdded)
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
        .onChange(of: page) {
            // After the sidebar's selection callback: changing the stack inside it re-enters
            // the sidebar's table (AppKit warns, and says it will assert).
            guard !path.isEmpty else { return }
            DispatchQueue.main.async { path = [] }
        }
        .safeAreaInset(edge: .bottom) { NowPlayingBar() }
    }

    @ViewBuilder
    private func pageView(_ page: Page) -> some View {
        switch page {
        case .recentlyAdded:
            AlbumGrid(title: "Recently Added") { try await model.calls.recentlyAdded() }
        case .newReleases:
            AlbumGrid(title: "New Releases") { try await model.calls.doNewReleases() }  // Kotlin adds "do" to names starting with "new"
        case .artists: ArtistList()
        case .favorites: FavoritesView()
        case .playlists: PlaylistList()
        case .radio: RadioView()
        case .search(let query): SearchView(query: query)
        }
    }

    @ViewBuilder
    private func routeView(_ route: Route) -> some View {
        switch route {
        case .album(let id): AlbumView(albumId: id)
        case .artist(let id): ArtistView(artistId: id)
        case .playlist(let id, let name): PlaylistView(playlistId: id, name: name)
        }
    }
}
