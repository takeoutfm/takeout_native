import MobileVLCKit
import Shared
import SwiftUI
import UIKit

/**
 * Live TV full screen on iPhone and iPad, in the video player's layout (`PlayerChrome`): the
 * program's progress in the slider's place with LIVE at its end (Jump to Live after a pause),
 * no seeking. Channels change from the Live page. A tap shows or hides the controls.
 */
struct LivePlayerScreen: View {
    let request: LiveRequest
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var live: LivePlayerModel
    @State private var showControls = true
    @State private var hideTask: Task<Void, Never>?

    init(request: LiveRequest, calls: SwiftCalls) {
        self.request = request
        _live = StateObject(wrappedValue: LivePlayerModel(calls: calls))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            // VLC's view would take the touches; the tap that shows the controls is SwiftUI's.
            LiveSurface(view: live.videoView).ignoresSafeArea().allowsHitTesting(false)
                .opacity(live.playingProgram ? 1 : 0)
            if let error = live.error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.white)
            } else if live.showSlate {
                LiveSlate(live: live)
            } else if live.tuned == nil || (live.playingProgram && live.isBuffering) {
                ProgressView().controlSize(.large).tint(.white)
            }
            if showControls || !live.isPlaying {
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
            live.play(request.channel)
            hideLater()
        }
        .onDisappear { live.close() }
    }

    private var controls: some View {
        PlayerChrome(close: { dismiss() }) {
            if live.playingProgram {
                Button { live.togglePause(); hideLater() } label: {
                    Image(systemName: live.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 44))
                }
                // the loading spinner takes its place, as in Apple's player
                .opacity(live.isBuffering ? 0 : 1)
            }
        } titles: {
            LiveBanner(live: live)
        } menus: {
            HStack(spacing: 20) { LiveTrackMenus(live: live) }
        } bar: {
            LiveProgress(live: live, jumped: hideLater)
        }
    }

    /// Hides the controls after 4 seconds without a touch, while playing.
    private func hideLater() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, live.isPlaying else { return }
            withAnimation { showControls = false }
        }
    }
}

/** VLC's video surface in SwiftUI. */
private struct LiveSurface: UIViewRepresentable {
    let view: UIView
    func makeUIView(context: Context) -> UIView { view }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
