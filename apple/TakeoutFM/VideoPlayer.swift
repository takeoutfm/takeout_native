import AppKit
import Combine
import Shared
import SwiftUI
import VLCKit

/** What the video window plays: a movie or episode id, from its saved place or the start. */
struct VideoPlayRequest: Codable, Hashable {
    let id: String
    var startOver = false
}

/** One embedded audio or subtitle track VLC reports (index -1: subtitles off). */
struct MediaTrack: Identifiable, Hashable {
    let index: Int32
    let name: String
    var id: Int32 { index }
}

/**
 * Plays one movie or episode with VLCKit, resuming where it was left and saving progress (every
 * 10 seconds while playing, on pause and on close) through the shared TakeoutProgress. An
 * episode that ends goes on to the next one.
 */
@MainActor
final class VideoPlayerModel: NSObject, ObservableObject, VLCMediaPlayerDelegate {
    @Published private(set) var details: VideoDetails?
    @Published private(set) var failure: String?
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = true
    @Published private(set) var timeMs: Int64 = 0
    @Published private(set) var lengthMs: Int64 = 0
    @Published private(set) var audioTracks: [MediaTrack] = []
    @Published private(set) var subtitleTracks: [MediaTrack] = []
    @Published private(set) var audioTrack: Int32 = -1
    @Published private(set) var subtitleTrack: Int32 = -1

    let videoView = VLCVideoView()
    private let calls: SwiftCalls
    private let cache: MediaCache
    private let player = VLCMediaPlayer()
    private var hasPlayed = false
    private var savedAt = Date.distantPast
    private var loading: Task<Void, Never>?
    /// This video's id with the stream proxy, while it's registered.
    private var proxied: String?

    init(calls: SwiftCalls, cache: MediaCache) {
        self.calls = calls
        self.cache = cache
        super.init()
        videoView.backColor = .black
        player.drawable = videoView
        player.delegate = self
    }

    func load(_ request: VideoPlayRequest) {
        save()
        player.stop()
        unregister()
        loading?.cancel()
        details = nil
        failure = nil
        hasPlayed = false
        isBuffering = true
        timeMs = 0
        lengthMs = 0
        audioTracks = []
        subtitleTracks = []
        loading = Task {
            do {
                let d = try await calls.videoDetails(id: request.id)
                details = d
                guard !d.videoUri.isEmpty else {
                    failure = "There's no video file for this title."
                    return
                }
                let resumeMs = request.startOver ? 0 : ((try? await calls.resumePositionMs(etag: d.etag))?.int64Value ?? 0)
                // Takeout's own streams go through the proxy (VLC can't send the token).
                var url = URL(string: d.videoUri)
                if let file = cache.localURL(d.etag) {
                    url = file  // downloaded: from disk, offline too
                } else if let authorization = calls.mediaAuthorization(url: d.videoUri) {
                    let stream = try await StreamProxy.shared.register(location: d.videoUri, authorization: authorization)
                    proxied = stream.id
                    url = stream.url
                }
                try Task.checkCancellation()
                guard let url else { throw URLError(.badURL) }
                let media = VLCMedia(url: url)
                if resumeMs > 0 { media.addOption(":start-time=\(Double(resumeMs) / 1000)") }
                player.media = media
                player.play()
            } catch is CancellationError {
            } catch {
                NSLog("Video \(request.id) failed: \(error)")
                failure = "Couldn't play this."
            }
        }
    }

    func togglePlay() {
        if player.isPlaying { player.pause() } else { player.play() }
    }

    func jump(seconds: Int32) {
        if seconds < 0 { player.jumpBackward(-seconds) } else { player.jumpForward(seconds) }
    }

    func seek(toMs ms: Int64) {
        player.time = VLCTime(int: Int32(clamping: ms))
        timeMs = ms
    }

    func selectAudio(_ index: Int32) {
        player.currentAudioTrackIndex = index
        audioTrack = index
        // The language picked becomes the preference for the next title, as on the phone.
        if let media = player.media {
            AudioTrackRanking.remember(AudioTrackRanking.candidates(media).first { $0.id == index }?.language)
        }
    }

    func selectSubtitle(_ index: Int32) {
        player.currentVideoSubTitleIndex = index
        subtitleTrack = index
    }

    /** Saves the place and stops (the window closed). */
    func close() {
        loading?.cancel()
        save()
        player.stop()
        unregister()
    }

    private func unregister() {
        if let proxied { StreamProxy.shared.unregister(proxied) }
        proxied = nil
    }

    /** Saves where we are, once playback really started (a failed load never resets it). */
    private func save() {
        guard hasPlayed, let etag = details?.etag, !etag.isEmpty else { return }
        savedAt = Date()
        #if DEBUG
        // For test runs that mustn't change the user's real progress: `-noProgress YES`.
        if UserDefaults.standard.bool(forKey: "noProgress") { return }
        #endif
        calls.reportProgress(etag: etag, positionMs: timeMs, durationMs: lengthMs)
    }

    private func refreshTracks() {
        defer {
            #if DEBUG
            NSLog("Tracks: audio \(audioTracks.map(\.name)) (\(audioTrack)), subtitles \(subtitleTracks.map(\.name)) (\(subtitleTrack))")
            #endif
        }
        // Named from the track details ("English · 5.1 · AC-3"): files often name every track alike.
        let details = player.media.map(AudioTrackRanking.candidates) ?? []
        audioTracks = Self.tracks(names: player.audioTrackNames, indexes: player.audioTrackIndexes)
            .filter { $0.index >= 0 }
            .map { track in
                details.first { $0.id == track.index }.map { MediaTrack(index: track.index, name: AudioTrackRanking.label($0)) } ?? track
            }
        subtitleTracks = Self.tracks(names: player.videoSubTitlesNames, indexes: player.videoSubTitlesIndexes)
        audioTrack = player.currentAudioTrackIndex
        subtitleTrack = player.currentVideoSubTitleIndex
    }

    private static func tracks(names: [Any], indexes: [Any]) -> [MediaTrack] {
        zip(names, indexes).compactMap { name, index in
            guard let i = (index as? NSNumber)?.int32Value else { return nil }
            return MediaTrack(index: i, name: name as? String ?? "Track \(i)")
        }
    }

    // MARK: VLCMediaPlayerDelegate (VLCKit calls these on the main thread)

    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor in self.stateChanged() }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor in self.timeChanged() }
    }

    private func stateChanged() {
        let state = player.state
        #if DEBUG
        if state != .buffering { NSLog("VLC \(VLCMediaPlayerStateToString(state)) at \(player.time.intValue) ms") }
        #endif
        isPlaying = player.isPlaying
        isBuffering = state == .opening || state == .buffering && !hasPlayed
        switch state {
        case .playing:
            if !hasPlayed { started() }
        case .paused:
            save()
        case .ended:
            save()
            // An episode ends: on to the next one, from its start.
            if let next = details?.nextEpisodeId { load(VideoPlayRequest(id: next, startOver: true)) }
        case .error:
            NSLog("VLC error playing \(details?.id ?? "?")")
            failure = "Couldn't play this."
        default:
            break
        }
    }

    private func started() {
        hasPlayed = true
        savedAt = Date()  // first save 10 s in, as on the phone
        chooseAudio()
        refreshTracks()
    }

    /// Switches to the best audio track for this title (once; the viewer can change it).
    private func chooseAudio() {
        guard let media = player.media else { return }
        let candidates = AudioTrackRanking.candidates(media)
        let best = AudioTrackRanking.best(candidates, current: player.currentAudioTrackIndex)
        #if DEBUG
        NSLog("Audio: \(candidates.map { "\($0.id) \($0.language ?? "?") \($0.codec) \($0.channels)ch \($0.bitrate) \($0.description)" }), VLC chose \(player.currentAudioTrackIndex) → best \(best.map(String.init) ?? "-")")
        #endif
        guard candidates.count > 1, let best, best != player.currentAudioTrackIndex else { return }
        player.currentAudioTrackIndex = best
    }

    private func timeChanged() {
        timeMs = Int64(player.time.intValue)
        lengthMs = Int64(player.media?.length.intValue ?? 0)
        isBuffering = false
        // VLCKit 3 reports .playing once, then keeps saying "buffering" while it plays; a
        // moving clock is what shows playback started.
        if !hasPlayed && player.isPlaying { started() }
        // Tracks can appear after the first frames (e.g. subtitles in a later cluster).
        if audioTracks.isEmpty || subtitleTracks.isEmpty { refreshTracks() }
        if Date().timeIntervalSince(savedAt) > 10 { save() }
    }
}

/** VLC's video surface in SwiftUI. */
struct VLCSurface: NSViewRepresentable {
    let view: VLCVideoView
    func makeNSView(context: Context) -> VLCVideoView { view }
    func updateNSView(_ nsView: VLCVideoView, context: Context) {}
}

/**
 * The video window: the picture, with controls that show while the pointer moves. Space plays
 * or pauses, ← → jump 10 seconds, F toggles full screen.
 */
struct VideoPlayerView: View {
    let request: VideoPlayRequest
    @EnvironmentObject private var model: AppModel
    @StateObject private var video: VideoPlayerModel
    @State private var showControls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubMs: Double?

    init(request: VideoPlayRequest, calls: SwiftCalls, cache: MediaCache) {
        self.request = request
        _video = StateObject(wrappedValue: VideoPlayerModel(calls: calls, cache: cache))
    }

    var body: some View {
        ZStack {
            Color.black
            VLCSurface(view: video.videoView)
            if let failure = video.failure {
                ContentUnavailableView(failure, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.white)
            } else if video.isBuffering {
                ProgressView().controlSize(.large)
            }
            if showControls || !video.isPlaying {
                VStack {
                    Spacer()
                    controls
                }
                .transition(.opacity)
            }
        }
        .navigationTitle(video.details?.title ?? "")
        .onContinuousHover { _ in poke() }
        .onTapGesture(count: 2) { toggleFullScreen() }
        .onTapGesture { video.togglePlay() }
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.space) { video.togglePlay(); return .handled }
        .onKeyPress(.leftArrow) { video.jump(seconds: -10); poke(); return .handled }
        .onKeyPress(.rightArrow) { video.jump(seconds: 10); poke(); return .handled }
        .onKeyPress("f") { toggleFullScreen(); return .handled }
        .onAppear {
            model.player.pause()
            video.load(request)
            poke()
        }
        .onDisappear { video.close() }
    }

    private var controls: some View {
        VStack(spacing: 8) {
            Slider(
                value: Binding(
                    get: { scrubMs ?? Double(video.timeMs) },
                    set: { scrubMs = $0 }
                ),
                in: 0...Double(max(video.lengthMs, 1)),
                onEditingChanged: { editing in
                    if !editing, let ms = scrubMs {
                        video.seek(toMs: Int64(ms))
                        scrubMs = nil
                    }
                }
            )
            HStack(spacing: 18) {
                if let previous = video.details?.previousEpisodeId {
                    Button { video.load(VideoPlayRequest(id: previous, startOver: true)) } label: {
                        Image(systemName: "backward.end.fill")
                    }
                    .help("Previous Episode")
                }
                Button { video.jump(seconds: -10) } label: { Image(systemName: "gobackward.10") }
                Button { video.togglePlay() } label: {
                    Image(systemName: video.isPlaying ? "pause.fill" : "play.fill").font(.title)
                }
                Button { video.jump(seconds: 10) } label: { Image(systemName: "goforward.10") }
                if let next = video.details?.nextEpisodeId {
                    Button { video.load(VideoPlayRequest(id: next, startOver: true)) } label: {
                        Image(systemName: "forward.end.fill")
                    }
                    .help("Next Episode")
                }
                Text("\(format(scrubMs.map { Int64($0) } ?? video.timeMs)) / \(format(video.lengthMs))")
                    .monospacedDigit()
                Spacer()
                if video.audioTracks.count > 1 {
                    Menu {
                        ForEach(video.audioTracks) { track in
                            Toggle(track.name, isOn: Binding(
                                get: { video.audioTrack == track.index },
                                set: { _ in video.selectAudio(track.index) }
                            ))
                        }
                    } label: { Image(systemName: "waveform") }
                    .menuStyle(.button)
                    .buttonStyle(.plain)  // so the icon takes the bar's white (a menu label is drawn by AppKit)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("Audio")
                }
                if !video.subtitleTracks.isEmpty {
                    Menu {
                        ForEach(video.subtitleTracks) { track in
                            Toggle(track.name, isOn: Binding(
                                get: { video.subtitleTrack == track.index },
                                set: { _ in video.selectSubtitle(track.index) }
                            ))
                        }
                    } label: { Image(systemName: "captions.bubble") }
                    .menuStyle(.button)
                    .buttonStyle(.plain)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("Subtitles")
                }
                Button { toggleFullScreen() } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }
                    .help("Full Screen")
            }
            .font(.title3)
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.white)
        // The bar is always over dark video: AppKit-drawn parts (slider, menus) follow this.
        .environment(\.colorScheme, .dark)
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom))
    }

    /// Shows the controls, then hides them after 3 seconds without movement.
    private func poke() {
        withAnimation { showControls = true }
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            withAnimation { showControls = false }
        }
    }

    private func toggleFullScreen() {
        NSApp.keyWindow?.toggleFullScreen(nil)
    }

    private func format(_ ms: Int64) -> String {
        let s = Int(ms / 1000)
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }
}
