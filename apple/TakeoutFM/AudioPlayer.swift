import AVFoundation
import Combine
import MediaPlayer
import Shared

/**
 * Music playback: an AVQueuePlayer over the queue's streams (FLAC, MP3 and M4A play natively;
 * the media token goes in each request's Authorization header), published for the Now Playing
 * bar, and wired to the system's Now Playing (Control Center, media keys). It tells the shared
 * ListenSession what plays (which reports listens), follows live streams' ICY titles, keeps
 * Recently Played's place, and saves podcast episodes' positions (synced, keyed by GUID).
 */
@MainActor
final class AudioPlayer: ObservableObject {
    @Published private(set) var items: [QueueItem] = []
    /// What the queue is ("Ride Lonesome", "Beck Radio", a podcast), for the queue's header.
    @Published private(set) var queueTitle = ""
    /// The queue as played, for Playback Caching's grouping.
    private var queue: MusicQueue?
    /// Off, the whole queue, or the current item.
    @Published var repeatMode: RepeatMode = .off

    enum RepeatMode { case off, all, one }
    @Published private(set) var index = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    /// A live stream (no end): the bar shows its current song instead of a seek bar.
    @Published private(set) var isLive = false {
        didSet {
            #if DEBUG
            if isLive != oldValue { NSLog("Live: \(isLive) for \(current?.title ?? "-")") }
            #endif
        }
    }
    /// The live stream's current ICY title ("Artist - Title"), when it sends one.
    @Published private(set) var streamTitle: String?

    private let calls: SwiftCalls
    private let cache: MediaCache
    private let player = AVQueuePlayer()
    private var timeObserver: Any?
    private var bag = Set<AnyCancellable>()
    private let listens: ListenSession
    private var metadata: MetadataReceiver?
    /// This queue's Recently Played entry, and when its place was last saved.
    private var historyId: String?
    private var savedAt = Date.distantPast
    private var episodeSavedAt = Date.distantPast
    #if DEBUG
    private var seekedNearEnd = false
    #endif

    var current: QueueItem? { items.indices.contains(index) ? items[index] : nil }

    /// What's playing: a stream's current song when it names one ("Artist - Title"), else the item's title.
    var displayTitle: String {
        guard let item = current else { return "" }
        guard let icy = streamTitle else { return item.title }
        return Self.split(icy)?.title ?? icy
    }

    /// Who: the artist, or for a stream's song "Artist · Station".
    var displaySubtitle: String {
        guard let item = current else { return "" }
        guard let icy = streamTitle else { return item.artist }
        guard let artist = Self.split(icy)?.artist else { return item.title }
        return "\(artist) · \(item.title)"
    }

    init(calls: SwiftCalls, cache: MediaCache) {
        self.calls = calls
        self.cache = cache
        listens = calls.listenSession()
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time) }
        }
        player.publisher(for: \.timeControlStatus)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in self?.playingChanged(status == .playing) }
            .store(in: &bag)
        // AVFoundation fails quietly: log why an item couldn't be opened or stopped short.
        player.publisher(for: \.currentItem)
            .compactMap { $0 }
            .flatMap { item in item.publisher(for: \.status).filter { $0 == .failed }.map { _ in item } }
            .receive(on: RunLoop.main)
            .sink { item in NSLog("Can't play \(Self.describe(item)): \(item.error.map { "\($0)" } ?? "unknown error")") }
            .store(in: &bag)
        NotificationCenter.default.publisher(for: AVPlayerItem.failedToPlayToEndTimeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] note in
                self?.listens.stopped()
                let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey]
                NSLog("Stopped short \((note.object as? AVPlayerItem).map(Self.describe) ?? ""): \(error.map { "\($0)" } ?? "")")
            }
            .store(in: &bag)
        NotificationCenter.default.publisher(for: AVPlayerItem.newErrorLogEntryNotification)
            .receive(on: RunLoop.main)
            .sink { note in
                guard let item = note.object as? AVPlayerItem, let event = item.errorLog()?.events.last else { return }
                NSLog("Stream error \(Self.describe(item)): \(event.errorStatusCode) \(event.errorDomain) \(event.errorComment ?? "")")
            }
            .store(in: &bag)
        #if DEBUG
        player.publisher(for: \.timeControlStatus)
            .sink { [weak self] status in
                NSLog("Player \(["paused", "waiting", "playing"][status.rawValue]) \(self?.player.reasonForWaitingToPlay?.rawValue ?? "") item \(self?.player.currentItem?.status.rawValue ?? -1)")
            }
            .store(in: &bag)
        #endif
        NotificationCenter.default.publisher(for: AVPlayerItem.didPlayToEndTimeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.advanced() }
            .store(in: &bag)
        remoteCommands()
    }

    /// Plays [queue] from its start track, or from [start] (at its beginning) when given.
    func play(_ queue: MusicQueue, shuffled: Bool = false, from start: Int? = nil) {
        saveProgress()
        saveEpisode()
        historyId = calls.recordPlayed(queue: queue)
        var list = queue.items
        var index = start ?? Int(queue.startIndex)
        var seconds = start == nil ? Double(queue.startPositionMs) / 1000 : 0
        if shuffled { list.shuffle(); index = 0; seconds = 0 }
        guard list.indices.contains(index) else { return }
        items = list
        queueTitle = queue.title
        self.queue = queue
        load(from: index, at: seconds)
        player.play()
    }

    func pause() {
        player.pause()
    }

    func togglePlay() {
        if player.timeControlStatus == .paused { player.play() } else { player.pause() }
    }

    func next() {
        guard index + 1 < items.count else { return }
        saveEpisode()
        load(from: index + 1, at: 0)
        player.play()
    }

    /// Adds [newItems] after the current item, or at the end ([last]); plays them if nothing is queued.
    func enqueue(_ newItems: [QueueItem], last: Bool) {
        guard !newItems.isEmpty else { return }
        guard !items.isEmpty else {
            items = newItems
            queueTitle = newItems[0].title
            load(from: 0, at: 0)
            player.play()
            return
        }
        items.insert(contentsOf: newItems, at: last ? items.count : index + 1)
        // AVQueuePlayer holds the current item and those after it: insert to match.
        var after: AVPlayerItem? = last ? nil : player.currentItem
        for item in newItems {
            guard let playerItem = playerItem(item) else { continue }
            player.insert(playerItem, after: after)
            if !last { after = playerItem }
        }
    }

    func cycleRepeat() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    /// Jumps to item [index] of the queue, from its start.
    func skip(to index: Int) {
        guard items.indices.contains(index), index != self.index else { return }
        saveEpisode()
        load(from: index, at: 0)
        player.play()
    }

    func previous() {
        if position > 3 || index == 0 {
            player.seek(to: .zero)
        } else {
            saveEpisode()
            load(from: index - 1, at: 0)
            player.play()
        }
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
    }

    func stop() {
        saveProgress()
        saveEpisode()
        player.removeAllItems()
        items = []
        queueTitle = ""
        queue = nil
        index = 0
        historyId = nil
        trackChanged()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    /// Queues the items from [start] on (AVQueuePlayer only plays forward).
    private func load(from start: Int, at seconds: Double) {
        player.removeAllItems()
        index = start
        for item in items[start...] {
            guard let playerItem = playerItem(item) else { continue }
            player.insert(playerItem, after: nil)
        }
        if seconds > 0 { seek(to: seconds) }
        trackChanged()
    }

    private func playerItem(_ item: QueueItem) -> AVPlayerItem? {
        // Downloaded: play the file (offline too).
        if let file = cache.localURL(item.etag) {
            #if DEBUG
            NSLog("Playing \(item.title) from its download")
            #endif
            return AVPlayerItem(asset: AVURLAsset(url: file, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true]))
        }
        guard let url = URL(string: item.uri) else { return nil }
        var headers: [String: String] = [:]
        if let auth = calls.mediaAuthorization(url: item.uri) { headers["Authorization"] = auth }
        // Precise timing: FLAC has no index AVFoundation trusts, so without it seeks land
        // well short of where the clock says (a jump to 2:00 of a 2:03 track played on for 50 s).
        let asset = AVURLAsset(url: url, options: [
            "AVURLAssetHTTPHeaderFieldsKey": headers,
            AVURLAssetPreferPreciseDurationAndTimingKey: true,
        ])
        let playerItem = AVPlayerItem(asset: asset)
        // Live streams name the song playing in their ICY metadata.
        if item.etag.isEmpty {
            if metadata == nil {
                metadata = MetadataReceiver { [weak self] title in self?.streamTitleChanged(title) }
            }
            let output = AVPlayerItemMetadataOutput(identifiers: nil)
            output.setDelegate(metadata, queue: .main)
            playerItem.add(output)
        }
        return playerItem
    }

    private static func describe(_ item: AVPlayerItem) -> String {
        (item.asset as? AVURLAsset)?.url.absoluteString ?? "item"
    }

    private func advanced() {
        // An episode that played to its end is complete.
        if let item = current, item.podcast, duration > 0 {
            calls.reportProgress(etag: item.etag, positionMs: durationMs, durationMs: durationMs)
        }
        keepIfCaching()
        if repeatMode == .one, current != nil {
            load(from: index, at: 0)
            player.play()
            return
        }
        guard index + 1 < items.count else {
            if repeatMode == .all, !items.isEmpty {
                load(from: 0, at: 0)
                player.play()
            }
            return
        }
        index += 1
        trackChanged()
    }

    /// Playback Caching: a track or episode played to its end is kept (downloaded, so it's
    /// fetched once more), listed with the queue it played in. Android keeps what it streamed
    /// as it goes; AVPlayer can't hand its bytes over. Live streams are never kept.
    private func keepIfCaching() {
        guard calls.playbackCaching, let item = current, !item.etag.isEmpty, !isLive,
              cache.localURL(item.etag) == nil, cache.status[item.etag] == nil else { return }
        if let queue {
            cache.download(queue, only: [item])
        } else {
            cache.download([item], group: "title:\(queueTitle)", groupTitle: queueTitle)
        }
    }

    private func trackChanged() {
        episodeSavedAt = Date()  // an episode's first save comes 10 s in
        streamTitle = nil
        isLive = false
        listens.track(item: current)
        updateNowPlaying()
    }

    private func playingChanged(_ playing: Bool) {
        isPlaying = playing
        listens.playing(isPlaying: playing, durationMs: durationMs)
        if !playing {
            saveProgress()
            saveEpisode()
        }
        updateNowPlaying()
    }

    private func streamTitleChanged(_ title: String) {
        guard title != streamTitle else { return }
        #if DEBUG
        NSLog("Stream title: \(title)")
        #endif
        streamTitle = title
        listens.streamTitle(icy: title)
        updateNowPlaying()
    }

    private var durationMs: Int64 { Int64(duration * 1000) }

    private func tick(_ time: CMTime) {
        position = time.seconds.isFinite ? time.seconds : 0
        let d = player.currentItem?.duration ?? .zero
        // Every item's duration is "indefinite" until it has loaded; only a ready one that still
        // has none is a live stream.
        isLive = player.currentItem?.status == .readyToPlay && d.isIndefinite
        duration = d.seconds.isFinite ? d.seconds : 0
        listens.tick(durationMs: durationMs)
        #if DEBUG
        // For testing what happens at a track's end without playing it all: `-seekNearEnd YES`.
        if UserDefaults.standard.bool(forKey: "seekNearEnd"), !seekedNearEnd, duration > 10, position > 1 {
            seekedNearEnd = true
            seek(to: duration - 3)
        }
        #endif
        if Date().timeIntervalSince(savedAt) > 5 { saveProgress() }
        if isPlaying && Date().timeIntervalSince(episodeSavedAt) > 10 { saveEpisode() }
    }

    /// Where a podcast episode got to (music tracks are left to the listen session).
    private func saveEpisode() {
        guard let item = current, item.podcast, !item.etag.isEmpty, position > 0 else { return }
        episodeSavedAt = Date()
        calls.reportProgress(etag: item.etag, positionMs: Int64(position * 1000), durationMs: durationMs)
    }

    /// Where Recently Played resumes this queue (a live stream starts afresh).
    private func saveProgress() {
        guard let historyId, current != nil else { return }
        savedAt = Date()
        calls.playedProgress(id: historyId, index: Int32(index), positionMs: isLive ? 0 : Int64(position * 1000))
    }

    private func updateNowPlaying() {
        guard let item = current else { return }
        // A stream's song is "Artist - Title"; the station goes where the album would.
        let song = streamTitle.flatMap(Self.split)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: song?.title ?? item.title,
            MPMediaItemPropertyArtist: song?.artist ?? item.artist,
            MPMediaItemPropertyAlbumTitle: song == nil ? item.album : item.title,
            MPNowPlayingInfoPropertyIsLiveStream: isLive,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
    }

    /// An ICY title as artist and title, when it has that form.
    static func split(_ icy: String) -> (artist: String, title: String)? {
        guard let pair = ListenRulesKt.parseStreamTitle(icy: icy) else { return nil }
        return (pair.first as String? ?? "", pair.second as String? ?? "")
    }

    private func remoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.togglePlayPauseCommand.addTarget { [weak self] _ in self?.togglePlay(); return .success }
        center.playCommand.addTarget { [weak self] _ in self?.player.play(); return .success }
        center.pauseCommand.addTarget { [weak self] _ in self?.player.pause(); return .success }
        center.nextTrackCommand.addTarget { [weak self] _ in self?.next(); return .success }
        center.previousTrackCommand.addTarget { [weak self] _ in self?.previous(); return .success }
    }
}

/** Receives a stream item's timed metadata and passes on its ICY StreamTitle. */
private final class MetadataReceiver: NSObject, AVPlayerItemMetadataOutputPushDelegate {
    private let onTitle: @MainActor (String) -> Void

    init(onTitle: @escaping @MainActor (String) -> Void) {
        self.onTitle = onTitle
    }

    func metadataOutput(
        _ output: AVPlayerItemMetadataOutput,
        didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup],
        from track: AVPlayerItemTrack?
    ) {
        let item = groups.flatMap(\.items).first {
            $0.identifier == .icyMetadataStreamTitle || $0.commonKey == .commonKeyTitle
        }
        guard let item else { return }
        Task { @MainActor [onTitle] in
            if let title = try? await item.load(.stringValue), !title.isEmpty { onTitle(title) }
        }
    }
}
