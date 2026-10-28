import SwiftUI
import Shared

@main
struct TakeoutFMApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if model.signedIn {
                    MainView()
                } else {
                    SignInView()
                }
            }
            .environmentObject(model)
            .environmentObject(model.player)
            .frame(minWidth: 900, minHeight: 600)
        }
        .commands {
            CommandMenu("Playback") {
                Button("Play/Pause") { model.player.togglePlay() }
                    .keyboardShortcut(.space, modifiers: [])
                Button("Next") { model.player.next() }
                    .keyboardShortcut(.rightArrow, modifiers: .command)
                Button("Previous") { model.player.previous() }
                    .keyboardShortcut(.leftArrow, modifiers: .command)
            }
        }
        WindowGroup("Video", id: "video", for: VideoPlayRequest.self) { $request in
            if let request {
                VideoPlayerView(request: request, calls: model.calls)
                    .environmentObject(model)
                    .frame(minWidth: 640, minHeight: 360)
            }
        }
        .defaultSize(width: 1280, height: 720)
        Settings {
            SettingsView().environmentObject(model)
        }
    }
}

/** The shared Kotlin core and the app-wide state, created once at launch. */
@MainActor
final class AppModel: ObservableObject {
    let core: TakeoutCore
    let calls: SwiftCalls
    let player: AudioPlayer
    @Published private(set) var signedIn = false
    @Published private(set) var hasPlaylists = false
    @Published private(set) var hasMovies = false
    @Published private(set) var hasShows = false
    /// Recently Played, newest first.
    @Published private(set) var recentlyPlayed: [PlayedQueue] = []
    /// ETags of the favorite tracks, for ♥ buttons.
    @Published private(set) var favoriteTracks: Set<String> = []
    /// Track Activity: report plays to the server.
    @Published var reportPlays: Bool {
        didSet { calls.reportPlays = reportPlays }
    }
    private var watches: [Watching] = []
    private var activeObserver: NSObjectProtocol?

    init() {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev"
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        // The database lives in Application Support (the sandbox container's), created if needed.
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TakeoutFM", isDirectory: true)
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        #if DEBUG
        let secureStore: KeyValueStore = DebugTokenStore(directory: support)
        #else
        let secureStore: KeyValueStore = KeychainStore(service: "com.takeoutfm.mac")
        #endif
        core = TakeoutCore(
            secureStore: secureStore,
            userAgent: "TakeoutFM-Mac/\(version) (takeoutfm.com; macOS \(os))",
            dataDirectory: support.path
        )
        calls = SwiftCalls(core: core)
        player = AudioPlayer(calls: calls)
        signedIn = calls.isSignedIn
        reportPlays = calls.reportPlays
        watches = [
            WatchKt.watch(flow: core.session.state) { [weak self] state in
                self?.signedIn = (state as? SessionState)?.isLoggedIn ?? false
            },
            WatchKt.watch(flow: core.library.index) { [weak self] index in
                let index = index as? LibraryIndex
                self?.hasPlaylists = index?.hasPlaylists ?? false
                self?.hasMovies = index?.hasMovies ?? false
                self?.hasShows = index?.hasShows ?? false
            },
            WatchKt.watch(flow: core.history.entries) { [weak self] entries in
                self?.recentlyPlayed = entries as? [PlayedQueue] ?? []
            },
            WatchKt.watch(flow: core.music.favoriteTrackEtags) { [weak self] etags in
                self?.favoriteTracks = etags as? Set<String> ?? []
            },
        ]
        Task { try? await calls.refreshLibrary() }
        Task { try? await calls.loadFavoriteTracks() }
        calls.retryListens()
        // As the Android apps do on returning to the foreground.
        activeObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.calls.retryListens() } }
        #if DEBUG
        // For testing playback without the UI: `TakeoutFM -playStations st-144,st-143` plays
        // each for 6 seconds.
        if let stations = UserDefaults.standard.string(forKey: "playStations") {
            for (i, station) in stations.split(separator: ",").map(String.init).enumerated() {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 6) { [weak self] in
                    guard let self else { return }
                    play { [calls] in try await calls.stationQueue(stationId: station) }
                }
            }
        }
        #endif
    }

    /** Plays what [load] returns (a queue from the server), logging a failure. */
    func play(shuffled: Bool = false, _ load: @escaping @MainActor () async throws -> MusicQueue) {
        Task {
            do {
                player.play(try await load(), shuffled: shuffled)
            } catch {
                NSLog("Play failed: \(error)")
            }
        }
    }

    func isFavorite(_ etag: String) -> Bool { favoriteTracks.contains(etag) }

    /** Adds or removes a favorite track (the shared set updates at once; reverted on failure). */
    func toggleFavorite(_ etag: String) {
        let wanted = !isFavorite(etag)
        Task {
            do {
                try await calls.setTrackFavorite(etag: etag, favorite: wanted)
            } catch {
                NSLog("Favorite failed: \(error)")
            }
        }
    }

    func signOut() {
        player.stop()
        core.session.signOut()
    }
}
