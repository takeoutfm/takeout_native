import SwiftUI
import Shared

/**
 * The iPhone app: the Android phone app's tabs (Home · Artists · Recent · Radio, with Search
 * as the iPhone's fifth tab), each keeping its own pages, with a mini player above the tab
 * bar that opens Now Playing as a card (swiped down to close).
 */
struct PhoneTabs: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            tab { HomeTab() }
                .tabItem { Label("Home", systemImage: "house") }
            tab { ArtistList() }
                .tabItem { Label("Artists", systemImage: "music.mic") }
            tab { RecentlyPlayedView() }
                .tabItem { Label("Recent", systemImage: "clock.arrow.circlepath") }
            tab { RadioView() }
                .tabItem { Label("Radio", systemImage: "dot.radiowaves.left.and.right") }
            tab { PhoneSearch() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
        }
        // A card over the app, swiped down to close (as Apple Music's).
        .sheet(isPresented: $model.showNowPlaying) {
            NowPlayingView()
                .presentationDragIndicator(.visible)
        }
        .overlay(alignment: .bottom) {
            if let message = model.message {
                Text(message)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.bottom, 120)
                    .transition(.opacity)
            }
        }
        .animation(.default, value: model.message)
    }

    private func tab<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack {
            content()
                .navigationDestination(for: Route.self) { RouteView(route: $0) }
        }
        .safeAreaInset(edge: .bottom) { MiniPlayer() }
    }
}

/**
 * The Home tab, as the Android app's and Apple Music's: rows of artwork to start from (new
 * music, Continue Watching, new films), then the library's lists. Pull to refresh.
 */
private struct HomeTab: View {
    @EnvironmentObject private var model: AppModel
    @State private var showSettings = false
    @State private var version = 0

    private struct Rows {
        var recentlyAdded: [Album] = []
        var newReleases: [Album] = []
        var continueWatching: [VideoCard] = []
        var newVideo: [VideoCard] = []
    }

    var body: some View {
        // Again when the library index arrives (at launch it can come just after): it says
        // whether there's video for the Continue Watching and New Films rows.
        Loaded(id: "\(version)/\(model.hasMovies || model.hasShows)", keepContent: true, load: load) { rows in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 28) {
                    AlbumRow(title: "Recently Added", albums: rows.recentlyAdded)
                    AlbumRow(title: "New Releases", albums: rows.newReleases)
                    VideoRow(title: "Continue Watching", cards: rows.continueWatching, wide: true)
                    VideoRow(title: "New Films", cards: rows.newVideo)
                    library
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .refreshable { version += 1 }
        }
        .navigationTitle("Home")
        .toolbar {
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                SettingsView()
                    .navigationTitle("Settings")
                    .toolbar { Button("Done") { showSettings = false } }
            }
        }
    }

    /// Each row is extra: one failing (say, no video on the server) leaves the others.
    private func load() async throws -> Rows {
        let calls = model.calls
        let video = model.hasMovies || model.hasShows
        async let added = (try? await calls.recentlyAdded()) ?? []
        async let new = (try? await calls.doNewReleases()) ?? []  // "do": Kotlin's prefix for "new…"
        async let watching = video ? ((try? await calls.continueWatching()) ?? []) : []
        async let films = video ? ((try? await calls.videoRecentlyAdded()) ?? []) : []
        return Rows(recentlyAdded: await added, newReleases: await new,
                    continueWatching: await watching, newVideo: await films)
    }

    /// The library's lists, as the Mac's sidebar has them.
    private var library: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle("Library").padding(.bottom, 8)
            link("Favorites", "heart", .favorites)
            if model.hasPlaylists { link("Playlists", "music.note.list", .playlists) }
            if model.hasPodcasts { link("Podcasts", "antenna.radiowaves.left.and.right", .podcasts) }
            link("Recently Added", "plus.square.on.square", .recentlyAdded)
            link("New Releases", "sparkles", .newReleases)
            if model.hasGuide { link("Live", "dot.radiowaves.up.forward", .live) }
            if model.hasMovies || model.hasShows { link("Watch", "play.tv", .watch) }
            if model.hasMovies { link("Movies", "film", .movies) }
            if model.hasShows { link("TV Shows", "tv", .shows) }
            link("Downloads", "arrow.down.circle", .downloads)
        }
    }

    // Value links, as every page's own are: a view-pushed page's Route links don't reach the
    // stack's navigationDestination.
    private func link(_ title: String, _ icon: String, _ list: LibraryList) -> some View {
        NavigationLink(value: Route.list(list)) {
            HStack {
                Label(title, systemImage: icon).font(.title3)
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) { Divider() }
    }
}

/** Search: the server's syntax ("artist:Beck"), searched when submitted. */
private struct PhoneSearch: View {
    @State private var query = ""
    @State private var submitted = ""

    var body: some View {
        Group {
            if submitted.isEmpty {
                ContentUnavailableView("Search", systemImage: "magnifyingglass",
                                       description: Text("Artists, albums, tracks, stations, movies and shows."))
            } else {
                SearchView(query: submitted)
            }
        }
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Artists, albums, tracks")
        .onSubmit(of: .search) {
            let q = query.trimmingCharacters(in: .whitespaces)
            if !q.isEmpty { submitted = q }
        }
    }
}

/** Above the tab bar while something is queued: tap for Now Playing. */
private struct MiniPlayer: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var player: AudioPlayer
    @State private var presses = 0

    var body: some View {
        if let item = player.current {
            HStack(spacing: 12) {
                Artwork(url: item.imageUri, symbol: "music.note").frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.displayTitle).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(player.displaySubtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Button { presses += 1; player.togglePlay() } label: {
                    Image(systemName: player.isPlaying ? (player.isLive ? "stop.fill" : "pause.fill") : "play.fill")
                        .font(.title2)
                        .frame(width: 44, height: 44)
                }
                .sensoryFeedback(.impact(weight: .light), trigger: presses)
                if !player.isLive {
                    Button { player.next() } label: {
                        Image(systemName: "forward.fill").font(.title3).frame(width: 36, height: 44)
                    }
                }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.bar, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 8)
            .contentShape(Rectangle())
            .onTapGesture { model.showNowPlaying = true }
        }
    }
}
