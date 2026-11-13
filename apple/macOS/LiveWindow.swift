import AppKit
import Shared
import SwiftUI

/**
 * The Live window: one channel at a time, picked from the Live page (picking another retunes
 * this window). Laid out as the video window, with live's differences: the program's progress
 * in the slider's place with LIVE at its end (Jump to Live after a pause), no seeking. Space pauses, L jumps to
 * live, F toggles full screen.
 */
struct LiveWindowView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var live: LivePlayerModel
    @State private var showControls = true
    @State private var hideTask: Task<Void, Never>?

    init(calls: SwiftCalls) {
        _live = StateObject(wrappedValue: LivePlayerModel(calls: calls))
    }

    var body: some View {
        ZStack {
            Color.black
            VLCSurface(view: live.videoView)
                .opacity(live.playingProgram ? 1 : 0)
            if let error = live.error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.white)
            } else if live.showSlate {
                LiveSlate(live: live)
            } else if live.tuned == nil || (live.playingProgram && live.isBuffering) {
                ProgressView().controlSize(.large)
            }
            if showControls || !live.isPlaying {
                controls.transition(.opacity)
            }
        }
        .navigationTitle(live.channel.isEmpty ? "Live" : live.channel)
        .onContinuousHover { _ in poke() }
        .onTapGesture(count: 2) { toggleFullScreen() }
        .onTapGesture { live.togglePause(); poke() }
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.space) { live.togglePause(); poke(); return .handled }
        .onKeyPress("l") { if live.behind { live.tune() }; return .handled }
        .onKeyPress("f") { toggleFullScreen(); return .handled }
        .onAppear {
            model.player.pause()
            if let request = model.liveRequest { live.play(request.channel) }
            poke()
        }
        .onChange(of: model.liveRequest) {
            if let request = model.liveRequest {
                model.player.pause()
                live.play(request.channel)
                poke()
            }
        }
        .onDisappear {
            live.close()
            model.liveRequest = nil
        }
    }

    /** As the video window's bar: the titles, the program's progress (LIVE at its end), the buttons. */
    private var controls: some View {
        VStack(spacing: 0) {
            Spacer()
            VStack(alignment: .leading, spacing: 8) {
                LiveBanner(live: live)
                LiveProgress(live: live, jumped: poke)
                HStack(spacing: 18) {
                    if live.playingProgram {
                        Button { live.togglePause(); poke() } label: {
                            Image(systemName: live.isPlaying ? "pause.fill" : "play.fill").font(.title)
                        }
                        .help(live.isPlaying ? "Pause" : "Play")
                    }
                    Spacer()
                    LiveTrackMenus(live: live)
                        .menuStyle(.button)
                        .buttonStyle(.plain)  // so the icons take the bar's white (a menu label is drawn by AppKit)
                    Button { toggleFullScreen() } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }
                        .help("Full Screen")
                }
                .font(.title3)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom))
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.white)
        // The bar is always over dark video: AppKit-drawn parts (menus) follow this.
        .environment(\.colorScheme, .dark)
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
}
