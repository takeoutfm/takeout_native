import AVFoundation
import Combine
import MediaPlayer
import Shared
import SwiftUI

/**
 * Music playback: an AVQueuePlayer over the queue's streams (FLAC, MP3 and M4A play natively;
 * the media token goes in each request's Authorization header), published for the Now Playing
 * bar, and wired to the system's Now Playing (Control Center, media keys). It tells the shared
 * ListenSession what plays (which reports listens), follows live streams' ICY titles, keeps
 * Recently Played's place, and saves podcast episodes' positions (synced, keyed by GUID).
 *
 * A live station's tracks (items with a `LiveSlot`) follow its schedule (RADIO-8…12), by the
 * shared `LiveRadio` rules: each track as it starts is checked against the server's clock (more
 * than 5 s off seeks to where the station is; a stale queue tunes in again) and the next tracks
 * are fetched and appended; skip, seek and repeat are refused; resuming after a short pause
 * plays on behind live, a long one tunes in again.
 */
@MainActor
final class AudioPlayer: ObservableObject {
    @Published private(set) var items: [QueueItem] = []
    /// What the queue is ("Ride Lonesome", "Beck Radio", a podcast), for the queue's header.
    @Published private(set) var queueTitle = ""
    /// The queue as played, for Playback Caching's grouping.
    private var queue: MusicQueue?
    /// Off, the whole queue, or the current item. Off and All are kept (`calls.repeatAll`) for
    /// later queues and launches; One lasts until something else is played.
    @Published var repeatMode: RepeatMode = .off {
        didSet {
            switch repeatMode {
            case .off: calls.repeatAll = false
            case .all: calls.repeatAll = true
            case .one: break
            }
        }
    }
    /// The kept mode, which a new queue starts with.
    private var savedRepeat: RepeatMode { calls.repeatAll ? .all : .off }

    enum RepeatMode { case off, all, one }
    @Published private(set) var index = 0
    /// Why an item couldn't play, as it happens (PLAY-12); the app shows it.
    let failures = PassthroughSubject<String, Never>()
    @Published private(set) var isPlaying = false
    /** Where the playing item is, and its length: watched only by the views that show them. */
    let progress = PlaybackProgress()
    var position: Double { progress.position }
    var duration: Double { progress.duration }
    /// A live stream (no end): the bar shows its current song instead of a seek bar.
    @Published private(set) var isLive = false {
        didSet {
            #if DEBUG
            if isLive != oldValue { NSLog("Live: \(isLive) for \(current?.title ?? "-")") }
            #endif
        }
    }
    /// The playing item's cover (for the lock screen, and Now Playing's tint), once loaded.
    @Published private(set) var artwork: LoadedArtwork?
    /// The live stream's current ICY title ("Artist - Title"), when it sends one.
    @Published private(set) var streamTitle: String?
    /// A live station playing a delayed copy of itself, after a pause ("Behind live · Jump to live").
    @Published private(set) var behindLive = false

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
    /// Bumped by each new queue (and stop), so a live fetch that comes back late is dropped.
    private var generation = 0
    /// When a live station's track was paused, for resuming behind live or jumping (RADIO-12).
    private var livePausedAt: Date?
    private var liveFetching = false
    #if DEBUG
    private var seekedNearEnd = false
    #endif

    var current: QueueItem? { items.indices.contains(index) ? items[index] : nil }

    /// Where the playing track is on a live station's schedule, or nil.
    var liveSlot: LiveSlot? { current?.live }
    /// A live station's track: play / pause only; the queue is the station's schedule.
    var isLiveRadio: Bool { liveSlot != nil }

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
        repeatMode = calls.repeatAll ? .all : .off
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time) }
        }
        player.publisher(for: \.timeControlStatus)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in self?.playingChanged(status == .playing) }
            .store(in: &bag)
        player.publisher(for: \.timeControlStatus)
            .map { $0 == .paused }
            .removeDuplicates()
            .receive(on: RunLoop.main)
            .sink { [weak self] paused in self?.pausedChanged(paused) }
            .store(in: &bag)
        // AVFoundation fails quietly: say why an item couldn't be opened or stopped short, and go
        // on with the next (PLAY-12).
        player.publisher(for: \.currentItem)
            .compactMap { $0 }
            .flatMap { item in item.publisher(for: \.status).filter { $0 == .failed }.map { _ in item } }
            .receive(on: RunLoop.main)
            .sink { [weak self] item in
                NSLog("Can't play \(Self.describe(item)): \(item.error.map { "\($0)" } ?? "unknown error")")
                self?.couldNotPlay(item.error)
            }
            .store(in: &bag)
        NotificationCenter.default.publisher(for: AVPlayerItem.failedToPlayToEndTimeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] note in
                self?.listens.stopped()
                let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
                NSLog("Stopped short \((note.object as? AVPlayerItem).map(Self.describe) ?? ""): \(error.map { "\($0)" } ?? "")")
                self?.couldNotPlay(error)
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
        generation += 1
        behindLive = false
        livePausedAt = nil
        liveFetching = false
        if repeatMode == .one { repeatMode = savedRepeat }
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
        guard !isLiveRadio, index + 1 < items.count else { return }
        saveEpisode()
        load(from: index + 1, at: 0)
        player.play()
    }

    /// Adds [newItems] after the current item, or at the end ([last]); plays them if nothing is queued.
    func enqueue(_ newItems: [QueueItem], last: Bool) {
        guard !newItems.isEmpty else { return }
        // Nothing queued, or a live station (whose queue is its schedule): play them instead.
        guard !items.isEmpty, !isLiveRadio else {
            saveProgress()
            historyId = nil
            generation += 1
            behindLive = false
            livePausedAt = nil
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
        guard !isLiveRadio else { return }
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    /// Jumps to item [index] of the queue, from its start.
    func skip(to index: Int) {
        guard !isLiveRadio, items.indices.contains(index), index != self.index else { return }
        saveEpisode()
        load(from: index, at: 0)
        player.play()
    }

    func previous() {
        guard !isLiveRadio else { return }
        if position > 3 || index == 0 {
            player.seek(to: .zero)
        } else {
            saveEpisode()
            load(from: index - 1, at: 0)
            player.play()
        }
    }

    func seek(to seconds: Double) {
        guard !isLiveRadio else { return }
        seekPlayer(to: seconds)
    }

    private func seekPlayer(to seconds: Double) {
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
        generation += 1
        behindLive = false
        livePausedAt = nil
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
        if seconds > 0 { seekPlayer(to: seconds) }
        trackChanged()
        liveStarted(at: seconds)
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

    /**
     * The current item couldn't play: why, for the window's message (`failures`), then the next
     * item, as on the phone. A live stream has no next to go to.
     */
    private func couldNotPlay(_ error: Error?) {
        let title = current.map { "“\($0.title)”" } ?? "this"
        failures.send("Can't play \(title): \(Self.reason(error)).")
        guard index + 1 < items.count else { return }
        // A live station goes on to the next track too, where the station is by then.
        saveEpisode()
        load(from: index + 1, at: 0)
        player.play()
    }

    /** Why AVFoundation couldn't play an item, in words the viewer can act on. */
    static func reason(_ error: Error?) -> String {
        guard let error = error as NSError? else { return "unknown error" }
        let errors = [error] + ((error.userInfo[NSUnderlyingErrorKey] as? NSError).map { [$0] } ?? [])
        for e in errors {
            if e.domain == NSURLErrorDomain {
                switch e.code {
                case NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorTimedOut,
                     NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed:
                    return "the server can't be reached"
                default: break
                }
            }
            // CoreMedia's HTTP answers
            if e.domain == "CoreMediaErrorDomain" {
                switch e.code {
                case -12938: return "it isn't on the server"
                case -12660, -12937: return "the server refused it"
                default: break
                }
            }
            // Core Audio's file errors under AVFoundation's -11800: 'typ?', 'fmt?', 'dta?'
            if e.domain == NSOSStatusErrorDomain, [1954115647, 1718449215, 1685348671].contains(e.code) {
                return "its format isn't supported"
            }
            if e.domain == AVFoundationErrorDomain {
                switch AVError.Code(rawValue: e.code) {
                case .fileFormatNotRecognized, .decoderNotFound, .decoderTemporarilyUnavailable, .failedToParse:
                    return "its format isn't supported"
                case .serverIncorrectlyConfigured, .contentIsUnavailable:
                    return "the server couldn't provide it"
                default: break
                }
            }
        }
        return error.localizedDescription.trimmingCharacters(in: CharacterSet(charactersIn: ". "))
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
        if isLiveRadio {
            guard index + 1 < items.count else {
                // The schedule ran out (no fetch came back): tune in again.
                jumpToLive()
                return
            }
            index += 1
            trackChanged()
            liveStarted(at: 0)
            return
        }
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
        // Marked cached: kept within the cache size, unlike the viewer's downloads.
        if let queue {
            cache.download(queue, only: [item], cached: true)
        } else {
            cache.download([item], group: "title:\(queueTitle)", groupTitle: queueTitle, cached: true)
        }
    }

    private func trackChanged() {
        episodeSavedAt = Date()  // an episode's first save comes 10 s in
        streamTitle = nil
        isLive = false
        listens.track(item: current)
        // played again: the most recently used, for the cache purge's order
        if let etag = current?.etag { cache.touch(etag) }
        loadArtwork()
        updateNowPlaying()
    }

    private var artworkTask: Task<Void, Never>?

    /// The new item's cover; an album's tracks share one, so it's only fetched when it changes.
    private func loadArtwork() {
        let url = current?.imageUri ?? ""
        guard url != artwork?.url else { return }
        artworkTask?.cancel()
        artwork = nil
        artworkTask = Task {
            let loaded = await LoadedArtwork.load(url)
            guard !Task.isCancelled, current?.imageUri == url else { return }
            artwork = loaded
            updateNowPlaying()
        }
    }

    /// The player paused or went on: a live station's pause and resume (RADIO-12).
    private func pausedChanged(_ paused: Bool) {
        guard let slot = liveSlot else { return }
        if paused {
            if livePausedAt == nil { livePausedAt = Date() }
            return
        }
        guard livePausedAt != nil else { return }
        livePausedAt = nil
        switch calls.liveResume(slot: slot, positionMs: Int64(position * 1000)) {
        case .jump: jumpToLive()
        case .behind: behindLive = true
        default: break
        }
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
        // Stream History (RADIO-5): the song, on this station, listed under Recently Played.
        if let item = current {
            calls.streamSongHeard(station: item.title, imageUri: item.imageUri, title: title)
        }
        updateNowPlaying()
    }

    private var durationMs: Int64 { Int64(duration * 1000) }

    private func tick(_ time: CMTime) {
        let d = player.currentItem?.duration ?? .zero
        // Every item's duration is "indefinite" until it has loaded; only a ready one that still
        // has none is a live stream. (Set only when it changes: each set redraws every view
        // watching the player, twice a second.)
        let live = player.currentItem?.status == .readyToPlay && d.isIndefinite
        if live != isLive { isLive = live }
        progress.update(
            position: time.seconds.isFinite ? time.seconds : 0,
            duration: d.seconds.isFinite ? d.seconds : 0
        )
        listens.tick(durationMs: durationMs)
        #if DEBUG
        // For testing what happens at a track's end without playing it all: `-seekNearEnd YES`
        // (a live station's track too). 3 s from the end failed the next item on the iPhone
        // simulator (AVFoundation -12860, as it handed over gaplessly); 10 s leaves room.
        if UserDefaults.standard.bool(forKey: "seekNearEnd"), !seekedNearEnd, duration > 20, position > 1 {
            seekedNearEnd = true
            seekPlayer(to: duration - 10)
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
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song?.title ?? item.title,
            MPMediaItemPropertyArtist: song?.artist ?? item.artist,
            // A live station's track: the station (and show) where the album would be (RADIO-11).
            MPMediaItemPropertyAlbumTitle: item.live?.label ?? (song == nil ? item.album : item.title),
            MPNowPlayingInfoPropertyIsLiveStream: isLive,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        // The cover on the lock screen, in Control Center and the Mac's Now Playing.
        if let image = artwork?.image {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
        // A live station's track: play / pause only (RADIO-10).
        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = item.live == nil
        center.previousTrackCommand.isEnabled = item.live == nil
    }

    /// An ICY title as artist and title, when it has that form.
    static func split(_ icy: String) -> (artist: String, title: String)? {
        guard let pair = ListenRulesKt.parseStreamTitle(icy: icy) else { return nil }
        return (pair.first as String? ?? "", pair.second as String? ?? "")
    }

    // ---- Live radio (the shared LiveRadio rules) ----

    /// A live track started [seconds] in: keep to the schedule, then fetch what comes next.
    private func liveStarted(at seconds: Double) {
        guard let slot = liveSlot else { return }
        let move = calls.liveCheck(slot: slot, positionMs: Int64(seconds * 1000), behindLive: behindLive)
        switch move.step {
        case .jump:
            jumpToLive()
            return
        case .seek:
            #if DEBUG
            NSLog("Live: \(current?.title ?? "") is off the schedule, to \(move.positionMs / 1000) s")
            #endif
            seekPlayer(to: Double(move.positionMs) / 1000)
        default: break
        }
        fetchMoreLive()
    }

    /// Appends the station's next tracks when few are left; each track start asks, and a failure
    /// waits for the next.
    private func fetchMoreLive() {
        guard !liveFetching, LiveRadio.shared.needsMore(left: Int32(items.count - index - 1)) else { return }
        let queued = items.filter { $0.live != nil }
        guard !queued.isEmpty else { return }
        liveFetching = true
        let generation = self.generation
        Task {
            let more = try? await calls.liveMore(queued: queued)
            guard generation == self.generation else { return }
            liveFetching = false
            guard let last = items.last?.live else { return }
            let fresh = (more ?? []).filter {
                guard let slot = $0.live else { return false }
                return slot.stationId == last.stationId && slot.startMs > last.startMs
            }
            guard !fresh.isEmpty else { return }
            #if DEBUG
            NSLog("Live: \(fresh.count) more on \(last.stationName)")
            #endif
            items.append(contentsOf: fresh)
            for item in fresh {
                guard let playerItem = playerItem(item) else { continue }
                player.insert(playerItem, after: nil)
            }
        }
    }

    /// Tunes in to the playing live station again: the track on now, from where it is.
    func jumpToLive() {
        guard let slot = liveSlot else { return }
        let generation = self.generation
        Task {
            do {
                let queue = try await calls.stationQueue(stationId: slot.stationId)
                guard generation == self.generation else { return }
                play(queue)
            } catch {
                NSLog("Jump to live failed: \(error)")
            }
        }
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

/**
 * The playing item's position and length, kept apart from `AudioPlayer`: they change twice a
 * second while playing, and in the player every view watching it (whole lists among them)
 * would redraw each time. Only the seek bars watch this (`WithProgress`).
 */
@MainActor
final class PlaybackProgress: ObservableObject {
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0

    fileprivate func update(position: Double, duration: Double) {
        if position != self.position { self.position = position }
        if duration != self.duration { self.duration = duration }
    }
}

/** [content] redrawn as the playing item's position moves, without the views around it. */
struct WithProgress<Content: View>: View {
    @ObservedObject var progress: PlaybackProgress
    @ViewBuilder let content: (PlaybackProgress) -> Content

    var body: some View { content(progress) }
}
