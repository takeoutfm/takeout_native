import AVFoundation
import Combine
import MediaPlayer
import Shared

/**
 * Music playback: an AVQueuePlayer over the queue's streams (FLAC, MP3 and M4A play natively;
 * the media token goes in each request's Authorization header), published for the Now Playing
 * bar, and wired to the system's Now Playing (Control Center, media keys).
 */
@MainActor
final class AudioPlayer: ObservableObject {
    @Published private(set) var items: [QueueItem] = []
    @Published private(set) var index = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0

    private let calls: SwiftCalls
    private let player = AVQueuePlayer()
    private var timeObserver: Any?
    private var bag = Set<AnyCancellable>()

    var current: QueueItem? { items.indices.contains(index) ? items[index] : nil }

    init(calls: SwiftCalls) {
        self.calls = calls
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time) }
        }
        player.publisher(for: \.timeControlStatus)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in self?.isPlaying = status == .playing; self?.updateNowPlaying() }
            .store(in: &bag)
        NotificationCenter.default.publisher(for: AVPlayerItem.didPlayToEndTimeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.advanced() }
            .store(in: &bag)
        remoteCommands()
    }

    func play(_ queue: MusicQueue, shuffled: Bool = false) {
        _ = calls.recordPlayed(queue: queue)
        var list = queue.items
        var start = Int(queue.startIndex)
        if shuffled { list.shuffle(); start = 0 }
        items = list
        load(from: start, at: shuffled ? 0 : Double(queue.startPositionMs) / 1000)
        player.play()
    }

    func togglePlay() {
        if player.timeControlStatus == .paused { player.play() } else { player.pause() }
    }

    func next() {
        guard index + 1 < items.count else { return }
        load(from: index + 1, at: 0)
        player.play()
    }

    func previous() {
        if position > 3 || index == 0 {
            player.seek(to: .zero)
        } else {
            load(from: index - 1, at: 0)
            player.play()
        }
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
    }

    func stop() {
        player.removeAllItems()
        items = []
        index = 0
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
        updateNowPlaying()
    }

    private func playerItem(_ item: QueueItem) -> AVPlayerItem? {
        guard let url = URL(string: item.uri) else { return nil }
        var headers: [String: String] = [:]
        if let auth = calls.mediaAuthorization(url: item.uri) { headers["Authorization"] = auth }
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        return AVPlayerItem(asset: asset)
    }

    private func advanced() {
        if index + 1 < items.count { index += 1 }
        updateNowPlaying()
    }

    private func tick(_ time: CMTime) {
        position = time.seconds.isFinite ? time.seconds : 0
        let d = player.currentItem?.duration.seconds ?? 0
        duration = d.isFinite ? d : 0
    }

    private func updateNowPlaying() {
        guard let item = current else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: item.title,
            MPMediaItemPropertyArtist: item.artist,
            MPMediaItemPropertyAlbumTitle: item.album,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
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
