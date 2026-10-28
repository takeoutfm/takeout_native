import Combine
import SwiftUI
import Shared
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/** The shared Kotlin core and the app-wide state, created once at launch. */
@MainActor
final class AppModel: ObservableObject {
    let core: TakeoutCore
    let calls: SwiftCalls
    let player: AudioPlayer
    /// Downloads (music, episodes, movies and episodes), which playback reads first.
    let cache: MediaCache
    @Published private(set) var signedIn = false
    @Published private(set) var hasPlaylists = false
    @Published private(set) var hasMovies = false
    @Published private(set) var hasShows = false
    @Published private(set) var hasPodcasts = false
    /// Recently Played, newest first.
    @Published private(set) var recentlyPlayed: [PlayedQueue] = []
    /// ETags of the favorite tracks, for ♥ buttons.
    @Published private(set) var favoriteTracks: Set<String> = []
    /// Track Activity: report plays to the server.
    @Published var reportPlays: Bool {
        didSet { calls.reportPlays = reportPlays }
    }
    /// Playback Caching: keep tracks and episodes played to their end.
    @Published var playbackCaching: Bool {
        didSet { calls.playbackCaching = playbackCaching }
    }
    /// Submit listens to ListenBrainz, when connected.
    @Published var submitListens: Bool {
        didSet { calls.submitListens = submitListens }
    }
    /// System, Light or Dark (the shared setting, as on the phone).
    @Published var theme: ThemeMode {
        didSet {
            calls.theme = theme
            Self.apply(theme)
        }
    }
    /// The connected ListenBrainz account's user name, if any.
    @Published private(set) var listenBrainzUser: String?
    /// The full Now Playing screen is over the window.
    @Published var showNowPlaying = false
    /// A short message at the bottom of the window ("Playing Next"), cleared after a moment.
    @Published private(set) var message: String?
    private var messageTask: Task<Void, Never>?
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
        cache = MediaCache(calls: calls, directory: support)
        player = AudioPlayer(calls: calls, cache: cache)
        signedIn = calls.isSignedIn
        reportPlays = calls.reportPlays
        submitListens = calls.submitListens
        playbackCaching = calls.playbackCaching
        theme = calls.theme
        watches = [
            WatchKt.watch(flow: core.session.state) { [weak self] state in
                guard let self else { return }
                let now = (state as? SessionState)?.isLoggedIn ?? false
                // Just signed in: learn the library (which pages exist) and the favorites. The
                // sign-in's own refresh can fail quietly, leaving iPhone's Library half empty.
                if now && !signedIn {
                    Task { [calls] in
                        try? await calls.refreshLibrary()
                        try? await calls.loadFavoriteTracks()
                    }
                }
                signedIn = now
            },
            WatchKt.watch(flow: core.library.index) { [weak self] index in
                let index = index as? LibraryIndex
                self?.hasPlaylists = index?.hasPlaylists ?? false
                self?.hasMovies = index?.hasMovies ?? false
                self?.hasShows = index?.hasShows ?? false
                self?.hasPodcasts = index?.hasPodcasts ?? false
            },
            WatchKt.watch(flow: core.history.entries) { [weak self] entries in
                self?.recentlyPlayed = entries as? [PlayedQueue] ?? []
            },
            WatchKt.watch(flow: core.music.favoriteTrackEtags) { [weak self] etags in
                self?.favoriteTracks = etags as? Set<String> ?? []
            },
            WatchKt.watch(flow: core.listenBrainz.user) { [weak self] user in
                self?.listenBrainzUser = (user as? ListenBrainzUser)?.name
            },
        ]
        Self.apply(theme)
        Task { try? await calls.refreshLibrary() }
        Task { try? await calls.loadFavoriteTracks() }
        calls.retryListens()
        // As the Android apps do on returning to the foreground.
        #if os(macOS)
        let becameActive = NSApplication.didBecomeActiveNotification
        #else
        let becameActive = UIApplication.didBecomeActiveNotification
        #endif
        activeObserver = NotificationCenter.default.addObserver(
            forName: becameActive, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.calls.retryListens() } }
        #if DEBUG
        // For testing downloads without the UI: `TakeoutFM -downloadAlbum al-12276`, and
        // playing an album: `-playAlbum al-12276`.
        if let album = UserDefaults.standard.string(forKey: "downloadAlbum") {
            Task { [calls, cache] in
                guard let queue = try? await calls.albumQueue(albumId: album, startIndex: 0) else { return NSLog("No album \(album)") }
                cache.download(queue.items, group: album, groupTitle: queue.title)
            }
        }
        if let album = UserDefaults.standard.string(forKey: "playAlbum") {
            play { [calls] in try await calls.albumQueue(albumId: album, startIndex: 0) }
        }
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

    /** Plays what [load] returns (a queue from the server), logging a failure; [from]: that item, from its start. */
    func play(shuffled: Bool = false, from start: Int? = nil, _ load: @escaping @MainActor () async throws -> MusicQueue) {
        Task {
            do {
                player.play(try await load(), shuffled: shuffled, from: start)
            } catch {
                NSLog("Play failed: \(error)")
            }
        }
    }

    func applyTheme() { Self.apply(theme) }

    /// The theme as a SwiftUI colour scheme (nil: follow the system); iOS applies it at its root.
    var colorScheme: ColorScheme? {
        switch theme {
        case .light: return .light
        case .dark: return .dark
        default: return nil
        }
    }

    private static func apply(_ theme: ThemeMode) {
        #if os(macOS)
        switch theme {
        case .light: NSApp?.appearance = NSAppearance(named: .aqua)
        case .dark: NSApp?.appearance = NSAppearance(named: .darkAqua)
        default: NSApp?.appearance = nil  // follow the system
        }
        #endif
    }

    func isFavorite(_ etag: String) -> Bool { favoriteTracks.contains(etag) }

    /** Play Next / Add to Queue for one track, as on the phone: just the track (its server queue goes on with similar ones). */
    func enqueueTrack(_ trackId: String, last: Bool) {
        Task {
            do {
                let queue = try await calls.trackQueue(trackId: trackId)
                player.enqueue(Array(queue.items.prefix(1)), last: last)
                show(last ? "Added to Queue" : "Playing Next")
            } catch {
                NSLog("Enqueue failed: \(error)")
                show("Couldn't add that")
            }
        }
    }

    /** [items] next or last, with the same message. */
    func enqueue(_ items: [QueueItem], last: Bool) {
        player.enqueue(items, last: last)
        show(last ? "Added to Queue" : "Playing Next")
    }

    func show(_ text: String) {
        message = text
        messageTask?.cancel()
        messageTask = Task {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            message = nil
        }
    }

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
