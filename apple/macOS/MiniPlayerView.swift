import AppKit
import SwiftUI
import Shared

/** The menu bar player: what's playing, its controls, and a way back to the main window. */
struct MiniPlayerView: View {
    @EnvironmentObject private var player: AudioPlayer
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(spacing: 12) {
            if let item = player.current {
                Artwork(url: item.imageUri, symbol: "music.note")
                    .frame(width: 240, height: 240)
                VStack(spacing: 2) {
                    Text(player.displayTitle).font(.headline).lineLimit(2).multilineTextAlignment(.center)
                    Text(player.displaySubtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                if player.isLive {
                    HStack(spacing: 16) {
                        Text("LIVE")
                            .font(.caption.bold())
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.red, in: Capsule())
                            .foregroundStyle(.white)
                        Button { player.togglePlay() } label: {
                            Image(systemName: player.isPlaying ? "stop.fill" : "play.fill").font(.title)
                        }
                    }
                } else {
                    Slider(
                        value: Binding(get: { player.position }, set: { player.seek(to: $0) }),
                        in: 0...max(player.duration, 1)
                    )
                    .controlSize(.small)
                    HStack(spacing: 28) {
                        Button { player.previous() } label: { Image(systemName: "backward.fill") }
                        Button { player.togglePlay() } label: {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title)
                        }
                        Button { player.next() } label: { Image(systemName: "forward.fill") }
                    }
                    .font(.title3)
                }
                if !item.etag.isEmpty && !item.podcast {
                    FavoriteButton(etag: item.etag)
                }
            } else {
                ContentUnavailableView("Nothing Playing", systemImage: "music.note")
                    .frame(width: 240, height: 160)
            }
            Divider()
            HStack {
                Button("Open TakeoutFM", action: openMainWindow)
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .buttonStyle(.borderless)
        .padding(16)
        .frame(width: 272)
    }

    /// Brings the main window forward, or opens one if it was closed.
    private func openMainWindow() {
        NSApp.activate(ignoringOtherApps: true)
        // SwiftUI names the window after its WindowGroup id ("main-AppWindow-1").
        if let window = NSApp.windows.first(where: { $0.identifier?.rawValue.hasPrefix("main") == true }) {
            window.makeKeyAndOrderFront(nil)
        } else {
            openWindow(id: "main")
        }
    }
}
