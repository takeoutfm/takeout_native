import MobileVLCKit
import Shared
import SwiftUI
import UIKit

/** VLC's video surface in SwiftUI. */
private struct VLCSurface: UIViewRepresentable {
    let view: UIView
    func makeUIView(context: Context) -> UIView { view }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

/**
 * The full-screen video player on iPhone and iPad: the picture, with controls that a tap shows
 * or hides (they hide by themselves after a few seconds of playing). The shared
 * VideoPlayerModel does the rest, as on the Mac: the stream proxy, resume, saved progress, the
 * best audio track and the next episode.
 */
struct VideoPlayerScreen: View {
    let request: VideoPlayRequest
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
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
            Color.black.ignoresSafeArea()
            // VLC's view would take the touches; the tap that shows the controls is SwiftUI's.
            VLCSurface(view: video.videoView).ignoresSafeArea().allowsHitTesting(false)
            if let failure = video.failure {
                ContentUnavailableView(failure, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.white)
            } else if video.isBuffering {
                ProgressView().controlSize(.large).tint(.white)
            }
            if showControls || !video.isPlaying {
                controls.transition(.opacity)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation { showControls.toggle() }
            if showControls { hideLater() }
        }
        .statusBarHidden(!showControls)
        .persistentSystemOverlays(.hidden)
        .onAppear {
            model.player.pause()
            video.load(request)
            hideLater()
        }
        .onDisappear { video.close() }
    }

    private var controls: some View {
        VStack {
            HStack {
                Button("Done") { dismiss() }
                    .font(.headline)
                Spacer()
                Text(video.details?.title ?? "").font(.headline).lineLimit(1)
                Spacer()
                trackMenus
            }
            .padding()
            .background(LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom))
            Spacer()
            HStack(spacing: 44) {
                if let previous = video.details?.previousEpisodeId {
                    Button { video.load(VideoPlayRequest(id: previous, startOver: true)) } label: {
                        Image(systemName: "backward.end.fill").font(.title2)
                    }
                }
                Button { video.jump(seconds: -10); hideLater() } label: { Image(systemName: "gobackward.10").font(.title) }
                Button { video.togglePlay(); hideLater() } label: {
                    Image(systemName: video.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 44))
                }
                Button { video.jump(seconds: 10); hideLater() } label: { Image(systemName: "goforward.10").font(.title) }
                if let next = video.details?.nextEpisodeId {
                    Button { video.load(VideoPlayRequest(id: next, startOver: true)) } label: {
                        Image(systemName: "forward.end.fill").font(.title2)
                    }
                }
            }
            Spacer()
            VStack(spacing: 4) {
                Slider(
                    value: Binding(get: { scrubMs ?? Double(video.timeMs) }, set: { scrubMs = $0 }),
                    in: 0...Double(max(video.lengthMs, 1)),
                    onEditingChanged: { editing in
                        if editing {
                            hideTask?.cancel()
                        } else if let ms = scrubMs {
                            video.seek(toMs: Int64(ms))
                            scrubMs = nil
                            hideLater()
                        }
                    }
                )
                HStack {
                    Text(format(scrubMs.map { Int64($0) } ?? video.timeMs))
                    Spacer()
                    Text(format(video.lengthMs))
                }
                .font(.caption.monospacedDigit())
            }
            .padding()
            .background(LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom))
        }
        .foregroundStyle(.white)
        .tint(.white)
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var trackMenus: some View {
        HStack(spacing: 20) {
            if video.audioTracks.count > 1 {
                Menu {
                    Picker("Audio", selection: Binding(get: { video.audioTrack }, set: { video.selectAudio($0) })) {
                        ForEach(video.audioTracks) { Text($0.name).tag($0.index) }
                    }
                } label: { Image(systemName: "waveform").font(.title3) }
            }
            if !video.subtitleTracks.isEmpty {
                Menu {
                    Picker("Subtitles", selection: Binding(get: { video.subtitleTrack }, set: { video.selectSubtitle($0) })) {
                        ForEach(video.subtitleTracks) { Text($0.name).tag($0.index) }
                    }
                } label: { Image(systemName: "captions.bubble").font(.title3) }
            }
        }
    }

    /// Hides the controls after 4 seconds without a touch, while playing.
    private func hideLater() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, video.isPlaying else { return }
            withAnimation { showControls = false }
        }
    }

    private func format(_ ms: Int64) -> String {
        let s = Int(ms / 1000)
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }
}
