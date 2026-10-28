import AppKit
import Shared
import SwiftUI
import VLCKit

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
