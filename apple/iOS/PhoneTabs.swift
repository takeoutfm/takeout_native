import SwiftUI
import Shared

/**
 * The iPhone app, as the Android phone app: tabs (Library · Recent · Artists · Radio · Search),
 * each keeping its own pages, with a mini player above the tab bar that opens Now Playing.
 */
struct PhoneTabs: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            tab { LibraryHome() }
                .tabItem { Label("Library", systemImage: "music.note.house") }
            tab { RecentlyPlayedView() }
                .tabItem { Label("Recent", systemImage: "clock.arrow.circlepath") }
            tab { ArtistList() }
                .tabItem { Label("Artists", systemImage: "music.mic") }
            tab { RadioView() }
                .tabItem { Label("Radio", systemImage: "dot.radiowaves.left.and.right") }
            tab { PhoneSearch() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
        }
        .fullScreenCover(isPresented: $model.showNowPlaying) { NowPlayingView() }
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

/** The Library tab: the music and video lists, Downloads, and Settings. */
private struct LibraryHome: View {
    @EnvironmentObject private var model: AppModel
    @State private var showSettings = false

    var body: some View {
        List {
            Section("Music") {
                link("Recently Added", "plus.square.on.square", .recentlyAdded)
                link("New Releases", "sparkles", .newReleases)
                link("Favorites", "heart", .favorites)
                if model.hasPlaylists { link("Playlists", "music.note.list", .playlists) }
                if model.hasPodcasts { link("Podcasts", "antenna.radiowaves.left.and.right", .podcasts) }
            }
            if model.hasMovies || model.hasShows {
                Section("Video") {
                    link("Watch", "play.tv", .watch)
                    if model.hasMovies { link("Movies", "film", .movies) }
                    if model.hasShows { link("TV Shows", "tv", .shows) }
                }
            }
            Section("On This iPhone") {
                link("Downloads", "arrow.down.circle", .downloads)
            }
        }
        .navigationTitle("Library")
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

    // Value links, as every page's own are: a view-pushed page's Route links don't reach the
    // stack's navigationDestination.
    private func link(_ title: String, _ icon: String, _ list: LibraryList) -> some View {
        NavigationLink(value: Route.list(list)) { Label(title, systemImage: icon) }
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

    var body: some View {
        if let item = player.current {
            HStack(spacing: 12) {
                Artwork(url: item.imageUri, symbol: "music.note").frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.displayTitle).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(player.displaySubtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Button { player.togglePlay() } label: {
                    Image(systemName: player.isPlaying ? (player.isLive ? "stop.fill" : "pause.fill") : "play.fill")
                        .font(.title2)
                        .frame(width: 44, height: 44)
                }
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
