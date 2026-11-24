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
    /// The server has channels (/api/guide): the Live page.
    @Published private(set) var hasGuide = false
    /// The channel the Mac's Live window plays (a new request each time one is picked).
    @Published var liveRequest: LiveRequest?
    /// Recently Played, newest first.
    @Published private(set) var recentlyPlayed: [PlayedQueue] = []
    /// ETags of the favorite tracks, for ♥ buttons.
    @Published private(set) var favoriteTracks: Set<String> = []
    /// Songs heard on live streams on this device, newest first (RADIO-5).
    @Published private(set) var streamSongs: [StreamSong] = []
    /// The server's recommendation sets (LIB-3), from the library index; empty when it has none.
    @Published private(set) var recommendations: [RecommendationSet] = []
    /// MusicBrainz ids of the favorite artists (the shared `favoriteArtistIds`, LIB-6).
    @Published private(set) var favoriteArtists: Set<String> = []
    /// Track Activity: report plays to the server.
    @Published var reportPlays: Bool {
        didSet { calls.reportPlays = reportPlays }
    }
    /// Playback Caching: keep tracks and episodes played to their end.
    @Published var playbackCaching: Bool {
        didSet { calls.playbackCaching = playbackCaching }
    }
    /// Playback Caching's size: 0 for Auto, else bytes (the shared setting).
    @Published var cacheLimitBytes: Int64 {
        didSet {
            calls.cacheLimitBytes = cacheLimitBytes
            cache.purge()
        }
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
    /// The signed-in server, and who's signed in to it (/api/me) once it has said.
    @Published private(set) var endpoint = ""
    @Published private(set) var serverUser: String?
    /// The full Now Playing screen is over the window.
    @Published var showNowPlaying = false
    /// The user's playlists as the shared core last loaded them, kept current by every edit
    /// (`currentPlaylists`); nil until first loaded.
    @Published private(set) var playlists: [MusicPlaylist]?
    /// What New Playlist… in an Add to Playlist menu is for, while the name is asked.
    @Published var newPlaylistTarget: PlaylistTarget?
    /// The subscribed podcasts as the shared core last loaded them, kept current by subscribing
    /// and unsubscribing (`subscribedSeries`); nil until first loaded.
    @Published private(set) var subscribedPodcasts: [PodcastSeries]?
    /// Where each episode stands, kept apart: it changes every few seconds while one plays.
    let episodeOffsets: EpisodeOffsets
    /// A short message at the bottom of the window ("Playing Next"), cleared after a moment.
    @Published private(set) var message: String?
    private var messageTask: Task<Void, Never>?
    private var playerFailures: AnyCancellable?
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
        episodeOffsets = EpisodeOffsets(calls: calls)
        let cache = MediaCache(calls: calls, directory: support)
        let player = AudioPlayer(calls: calls, cache: cache)
        // The purge leaves what's playing.
        cache.playing = { [weak player] in player?.current?.etag }
        self.cache = cache
        self.player = player
        signedIn = calls.isSignedIn
        reportPlays = calls.reportPlays
        submitListens = calls.submitListens
        playbackCaching = calls.playbackCaching
        cacheLimitBytes = calls.cacheLimitBytes
        theme = calls.theme
        watches = [
            WatchKt.watch(flow: core.session.state) { [weak self] state in
                guard let self else { return }
                let session = state as? SessionState
                let now = session?.isLoggedIn ?? false
                endpoint = session?.endpoint ?? ""
                serverUser = session?.user
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
                self?.hasGuide = index?.hasGuide ?? false
            },
            WatchKt.watch(flow: core.streamHistory.songs) { [weak self] songs in
                self?.streamSongs = songs as? [StreamSong] ?? []
            },
            WatchKt.watch(flow: core.history.entries) { [weak self] entries in
                self?.recentlyPlayed = entries as? [PlayedQueue] ?? []
            },
            WatchKt.watch(flow: core.music.favoriteTrackEtags) { [weak self] etags in
                self?.favoriteTracks = etags as? Set<String> ?? []
            },
            WatchKt.watch(flow: core.video.recommendations()) { [weak self] sets in
                self?.recommendations = sets as? [RecommendationSet] ?? []
            },
            WatchKt.watch(flow: core.music.favoriteArtistIds) { [weak self] ids in
                self?.favoriteArtists = ids as? Set<String> ?? []
            },
            WatchKt.watch(flow: core.music.currentPlaylists) { [weak self] playlists in
                self?.playlists = playlists as? [MusicPlaylist]
            },
            WatchKt.watch(flow: core.podcasts.subscribedSeries) { [weak self] series in
                self?.subscribedPodcasts = series as? [PodcastSeries]
            },
            WatchKt.watch(flow: core.progress.offsets) { [weak self] offsets in
                self?.episodeOffsets.offsets = offsets as? [String: ApiOffset] ?? [:]
            },
            WatchKt.watch(flow: core.listenBrainz.user) { [weak self] user in
                self?.listenBrainzUser = (user as? ListenBrainzUser)?.name
            },
        ]
        Self.apply(theme)
        Task { try? await calls.refreshLibrary() }
        Task { try? await calls.loadFavoriteTracks() }
        // for the Add to Playlist menus (kept current by every edit)
        Task { _ = try? await calls.playlists() }
        // A track that can't play says why (the player goes on with the next).
        playerFailures = self.player.failures.receive(on: RunLoop.main).sink { [weak self] text in
            self?.show(text, for: .seconds(5))
        }
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
                cache.download(queue)  // grouped as the Download button groups it
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

    /** Adds or removes a favorite artist (the shared set updates at once; reverted on failure). */
    func toggleFavoriteArtist(_ artist: Artist) {
        let wanted = !favoriteArtists.contains(artist.arid)
        Task {
            do {
                try await calls.setArtistFavorite(arid: artist.arid, favorite: wanted)
            } catch {
                show(wanted ? "Couldn't add \(artist.name) to Favorites" : "Couldn't remove \(artist.name) from Favorites")
            }
        }
    }

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

    // MARK: Playlists (LIB-13)

    /** Adds [target] to [playlist], saying so. */
    func add(_ target: PlaylistTarget, to playlist: MusicPlaylist) {
        Task {
            do {
                switch target {
                case .track(let id): try await calls.addTrackToPlaylist(playlistId: playlist.id, trackId: id)
                case .album(let id): try await calls.addAlbumToPlaylist(playlistId: playlist.id, albumId: id)
                }
                show("Added to \(playlist.name)")
            } catch {
                show("Couldn't add to \(playlist.name)")
            }
        }
    }

    /** A new playlist named [name], with [target] in it. */
    func add(_ target: PlaylistTarget, toNewPlaylist name: String) {
        let name = name.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        Task {
            do {
                let playlist = try await calls.createPlaylist(name: name)
                add(target, to: playlist)
            } catch {
                show("Couldn't create \(name)")
            }
        }
    }

    /** [items] next or last, with the same message. */
    func enqueue(_ items: [QueueItem], last: Bool) {
        player.enqueue(items, last: last)
        show(last ? "Added to Queue" : "Playing Next")
    }

    func show(_ text: String, for duration: Duration = .seconds(2)) {
        message = text
        messageTask?.cancel()
        messageTask = Task {
            try? await Task.sleep(for: duration)
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

/** What an Add to Playlist menu adds (LIB-13): a track or a whole album, by id. */
enum PlaylistTarget: Hashable {
    case track(String)
    case album(String)
}
