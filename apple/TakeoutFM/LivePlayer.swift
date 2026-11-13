import Combine
import Shared
import SwiftUI
#if os(macOS)
import VLCKit
#else
import MobileVLCKit
#endif

/** A channel to watch live; a new one each time it's picked, so picking it again retunes. */
struct LiveRequest: Hashable, Identifiable {
    let channel: String
    let id = UUID()
}

/** Opens the live player on a channel: the Mac's Live window, iOS's full-screen player. */
struct PlayLiveAction {
    let run: (String) -> Void
    func callAsFunction(_ channel: String) { run(channel) }
}

private struct PlayLiveKey: EnvironmentKey {
    static let defaultValue = PlayLiveAction { _ in }
}

extension EnvironmentValues {
    var playLive: PlayLiveAction {
        get { self[PlayLiveKey.self] }
        set { self[PlayLiveKey.self] = newValue }
    }
}

/** The server's time now, in epoch ms, for a guide that arrived with [skewMs]. */
func serverNowMs(skewMs: Int64) -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000) + skewMs
}

/** "9:30 PM" for an epoch-ms time. */
func clockTime(_ ms: Int64) -> String {
    Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .omitted, time: .shortened)
}

/** A guide program's title, "Off Air" when it has left the library. */
func programTitle(_ p: LiveProgram) -> String {
    p.kind == "offair" ? "Off Air" : p.title
}

/**
 * Live TV with VLCKit, as the other apps' live players: plays whatever is on a channel from
 * where it is now by the server's clock, then follows the schedule (the shared `tune` decides
 * what's on: a program from its offset, break music fading out when the next program cuts it
 * off, or the up-next slate). Pausing puts you behind live until the program's file ends or
 * Jump to Live. Watch progress isn't written, so flipping past things doesn't fill Continue
 * Watching.
 */
@MainActor
final class LivePlayerModel: NSObject, ObservableObject, VLCMediaPlayerDelegate {
    @Published private(set) var channel = ""
    /** The last `tune` answer. */
    @Published private(set) var tuned: LiveTune?
    @Published private(set) var behind = false
    @Published private(set) var error: String?
    /** The server's time, in epoch ms, ticking along. */
    @Published private(set) var now: Int64 = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = false
    /** The program's file ended before its slot: the slate until the slot's end. */
    @Published private(set) var fileEnded = false
    @Published private(set) var audioTracks: [MediaTrack] = []
    @Published private(set) var subtitleTracks: [MediaTrack] = []
    @Published private(set) var audioTrack: Int32 = -1
    @Published private(set) var subtitleTrack: Int32 = -1

    #if os(macOS)
    let videoView = VLCVideoView()
    #else
    let videoView = UIView()
    #endif
    private let calls: SwiftCalls
    private let player = VLCMediaPlayer()
    /** Bumped by each tune, so a late answer for an old one is dropped. */
    private var generation = 0
    private var retune: Task<Void, Never>?
    private var clock: Timer?
    private var started = false
    /** The stream registered with the proxy, while one is. */
    private var proxied: String?
    /** The subtitle language last chosen here ("" off), for the programs after; nil: none yet. */
    private var subtitleLanguage: String?

    var program: LiveProgram? { tuned?.program }
    var track: LiveProgram? { tuned?.track }
    var next: LiveProgram? { tuned?.next }
    var playingProgram: Bool { tuned?.kind == "program" && !fileEnded }
    /** Showing the up-next slate: a break, a program off air, or a file that ended early. */
    var showSlate: Bool {
        guard let tuned, error == nil else { return false }
        return tuned.kind == "music" || tuned.kind == "slate" || fileEnded
    }

    init(calls: SwiftCalls) {
        self.calls = calls
        super.init()
        #if os(macOS)
        videoView.backColor = .black
        #else
        videoView.backgroundColor = .black
        #endif
        player.drawable = videoView
        player.delegate = self
    }

    /** Plays [name] from what's on now. */
    func play(_ name: String) {
        channel = name
        if clock == nil {
            clock = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
        }
        tune()
    }

    /** Asks what's on now and plays it. */
    func tune() {
        generation += 1
        let g = generation
        behind = false
        error = nil
        retune?.cancel()
        Task {
            let answer: LiveTune
            do {
                answer = try await calls.tune(channel: channel)
            } catch {
                guard g == generation else { return }
                tuned = nil
                stop()
                self.error = "Couldn't load the guide. Retrying…"
                wait(ms: 30_000)
                return
            }
            guard g == generation else { return }
            tuned = answer
            fileEnded = false
            now = serverNowMs(skewMs: answer.skewMs)
            switch answer.kind {
            case "program", "music":
                guard let p = answer.kind == "program" ? answer.program : answer.track else { return }
                await load(p.videoUri, offsetMs: answer.offsetMs, generation: g)
                wait(ms: answer.untilMs - serverNowMs(skewMs: answer.skewMs))
            case "slate":
                stop()
                wait(ms: answer.untilMs > 0 ? answer.untilMs - serverNowMs(skewMs: answer.skewMs) : 5 * 60_000)
            default:
                stop()
                self.error = "\(channel) isn't on right now."
                wait(ms: 30_000)
            }
        }
    }

    func togglePause() {
        guard playingProgram else { return }
        if player.isPlaying {
            player.pause()
            behind = true
        } else {
            player.play()
        }
    }

    func selectAudio(_ index: Int32) {
        player.currentAudioTrackIndex = index
        audioTrack = index
        // The language picked becomes the preference for the next title, as in the video player.
        if let media = player.media {
            AudioTrackRanking.remember(AudioTrackRanking.tracks(media).first { $0.id == index }?.language)
        }
    }

    func selectSubtitle(_ index: Int32) {
        player.currentVideoSubTitleIndex = index
        subtitleTrack = index
        // followed by language to the programs after
        subtitleLanguage = index < 0 ? "" : player.subtitleLanguages[index] ?? subtitleLanguage
    }

    /** Stops for good (the player closed). */
    func close() {
        generation += 1
        retune?.cancel()
        clock?.invalidate()
        clock = nil
        stop()
        tuned = nil
    }

    /** Tunes again in [ms] (a second at least, five minutes at most). */
    private func wait(ms: Int64) {
        retune?.cancel()
        let delay = min(max(ms, 1000), 5 * 60_000)
        retune = Task {
            try? await Task.sleep(for: .milliseconds(delay))
            guard !Task.isCancelled else { return }
            // Behind live, the program plays on until its file ends.
            if !(behind && playingProgram) { tune() }
        }
    }

    private func load(_ uri: String, offsetMs: Int64, generation g: Int) async {
        stop()
        isBuffering = true
        do {
            // Takeout's own streams go through the proxy (VLC can't send the token).
            var url = URL(string: uri)
            if let authorization = calls.mediaAuthorization(url: uri) {
                let stream = try await StreamProxy.shared.register(location: uri, authorization: authorization)
                guard g == generation else {
                    StreamProxy.shared.unregister(stream.id)
                    return
                }
                proxied = stream.id
                url = stream.url
            }
            guard let url else { throw URLError(.badURL) }
            let media = VLCMedia(url: url)
            if offsetMs > 0 { media.addOption(":start-time=\(Double(offsetMs) / 1000)") }
            player.media = media
            player.audio?.volume = 100
            player.play()
        } catch {
            guard g == generation else { return }
            NSLog("Live \(channel) failed: \(error)")
            isBuffering = false
            self.error = "Couldn't play this."
        }
    }

    private func stop() {
        player.stop()
        if let proxied { StreamProxy.shared.unregister(proxied) }
        proxied = nil
        started = false
        isPlaying = false
        isBuffering = false
        audioTracks = []
        subtitleTracks = []
    }

    /** The clock, and break music fading out. */
    private func tick() {
        guard let tuned else { return }
        now = serverNowMs(skewMs: tuned.skewMs)
        if tuned.kind == "music", tuned.fadeFromMs > 0, now >= tuned.fadeFromMs {
            let left = Double(tuned.untilMs - now) / Double(max(tuned.untilMs - tuned.fadeFromMs, 1))
            player.audio?.volume = Int32(max(0, min(1, left)) * 100)
        }
    }

    private func refreshTracks() {
        audioTracks = player.labelledAudioTracks
        subtitleTracks = player.subtitleTrackList
        audioTrack = player.currentAudioTrackIndex
        subtitleTrack = player.currentVideoSubTitleIndex
    }

    /** Playback started on a newly loaded file: its best audio track and the subtitles as chosen. */
    private func didStart() {
        started = true
        player.chooseBestAudioTrack()
        if let language = subtitleLanguage {
            let id = language.isEmpty ? nil : player.subtitleLanguages.first { $0.value == language }?.key
            player.currentVideoSubTitleIndex = id ?? -1
        }
        refreshTracks()
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
        isPlaying = player.isPlaying
        switch state {
        case .opening:
            isBuffering = true
        case .ended:
            // Behind live: back to whatever is on now. A file shorter than its slot: the slate
            // until the slot ends (the retune is already set for then).
            if behind {
                tune()
            } else if tuned?.kind == "program" {
                fileEnded = true
            }
        case .error:
            NSLog("VLC error playing live \(channel)")
            isBuffering = false
            error = "Couldn't play this."
        default:
            break
        }
    }

    private func timeChanged() {
        isPlaying = player.isPlaying
        isBuffering = false
        // VLCKit 3 keeps saying "buffering" while it plays; a moving clock shows it started.
        if !started && player.isPlaying { didStart() }
        // Tracks can appear after the first frames (e.g. subtitles in a later cluster).
        if started && (audioTracks.isEmpty || subtitleTracks.isEmpty) { refreshTracks() }
    }
}

// MARK: - Pieces of both apps' live players

/**
 * The live player's titles, over its progress as the video player's are over its slider: the
 * program, then the channel (and Behind live), then what's next.
 */
struct LiveBanner: View {
    @ObservedObject var live: LivePlayerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(heading)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
            Text(details)
                .font(.callout)
                .opacity(0.8)
                .lineLimit(1)
            if let next = live.next, live.program != nil {
                Text("Next: \(clockTime(next.startMs)) · \(programTitle(next))")
                    .font(.callout)
                    .opacity(0.7)
                    .lineLimit(1)
            }
        }
    }

    private var heading: String {
        if let p = live.program { return programTitle(p) }
        if let next = live.next { return "Up next: \(programTitle(next))" }
        return live.channel
    }

    private var details: String {
        [
            live.channel,
            live.program?.block ?? "",
            live.program?.kind == "episode" ? live.program?.subtitle ?? "" : "",
            live.tuned?.left ?? "",
            live.behind ? "Behind live" : "",
        ].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/** The up-next slate over a break (with its music) or a program off air. */
struct LiveSlate: View {
    @ObservedObject var live: LivePlayerModel

    var body: some View {
        VStack(spacing: 8) {
            if let track = live.track {
                HStack(spacing: 12) {
                    if !track.imageUri.isEmpty {
                        Artwork(url: track.imageUri, symbol: "music.note").frame(width: 56, height: 56)
                    }
                    VStack(alignment: .leading) {
                        Text(track.title).bold().lineLimit(1)
                        Text(track.subtitle).opacity(0.7).lineLimit(1)
                    }
                }
            } else if live.program != nil {
                Text("Off Air").bold()
            }
            Text(live.next == nil ? "Nothing scheduled yet" : "Up next").opacity(0.7)
            if let next = live.next {
                Text(programTitle(next))
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                if next.startMs > live.now {
                    Text("Starts in \(countdown(next.startMs - live.now))").monospacedDigit()
                }
            }
        }
        .padding(24)
        .frame(maxWidth: 420)
        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 12))
        .foregroundStyle(.white)
        .padding()
    }

    private func countdown(_ ms: Int64) -> String {
        let s = max(0, Int(ms / 1000))
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }
}

/**
 * The program's progress in the slider's place: its start, the bar and its end, then at the
 * bar's end a red LIVE while live, or a Jump to Live button once paused behind it.
 */
struct LiveProgress: View {
    @ObservedObject var live: LivePlayerModel
    /** Called when Jump to Live is used (the controls stay up a while). */
    var jumped: () -> Void = {}

    var body: some View {
        if let p = live.program {
            HStack(spacing: 10) {
                Text(clockTime(p.startMs))
                ProgressView(value: Double(min(max(live.now - p.startMs, 0), p.endMs - p.startMs)),
                             total: Double(max(p.endMs - p.startMs, 1)))
                    .tint(.white)
                Text(clockTime(p.endMs))
                if live.behind {
                    Button {
                        live.tune()
                        jumped()
                    } label: {
                        pill(Label("Jump to Live", systemImage: "forward.end.fill"))
                            .background(Color.white.opacity(0.25), in: Capsule())
                    }
                    .buttonStyle(.plain)
                } else {
                    pill(Text("LIVE")).background(Color.red, in: Capsule())
                }
            }
            .font(.caption.monospacedDigit())
            .lineLimit(1)
        }
    }

    private func pill(_ content: some View) -> some View {
        content
            .font(.caption.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .foregroundStyle(.white)
            .fixedSize()
    }
}

/** The audio and subtitle menus, as the video player's. */
struct LiveTrackMenus: View {
    @ObservedObject var live: LivePlayerModel

    var body: some View {
        if live.audioTracks.count > 1 {
            Menu {
                ForEach(live.audioTracks) { track in
                    Toggle(isOn: Binding(
                        get: { live.audioTrack == track.index },
                        set: { _ in live.selectAudio(track.index) }
                    )) {
                        Text(track.name)
                        if !track.detail.isEmpty { Text(track.detail) }
                    }
                }
            } label: { Image(systemName: "waveform") }
            .menuIndicator(.hidden)
            .fixedSize()
            .help("Audio")
        }
        if !live.subtitleTracks.isEmpty {
            Menu {
                ForEach(live.subtitleTracks) { track in
                    Toggle(track.name, isOn: Binding(
                        get: { live.subtitleTrack == track.index },
                        set: { _ in live.selectSubtitle(track.index) }
                    ))
                }
            } label: { Image(systemName: "captions.bubble") }
            .menuIndicator(.hidden)
            .fixedSize()
            .help("Subtitles")
        }
    }
}
