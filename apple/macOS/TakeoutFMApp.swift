import SwiftUI
import Shared

@main
struct TakeoutFMApp: App {
    @StateObject private var model = AppModel()
    @AppStorage("showMenuBarPlayer") private var showMenuBarPlayer = true

    var body: some Scene {
        WindowGroup(id: "main") {
            MacRoot()
            .environmentObject(model)
            .environmentObject(model.player)
            .environmentObject(model.cache)
            .frame(minWidth: 900, minHeight: 600)
            // AppModel is made before NSApp exists, so apply the saved theme once it does.
            .onAppear { model.applyTheme() }
        }
        .commands {
            CommandMenu("Playback") {
                Button("Now Playing") { model.showNowPlaying.toggle() }
                    .keyboardShortcut("l", modifiers: .command)
                Divider()
                Button("Play/Pause") { model.player.togglePlay() }
                    .keyboardShortcut(.space, modifiers: [])
                Button("Next") { model.player.next() }
                    .keyboardShortcut(.rightArrow, modifiers: .command)
                Button("Previous") { model.player.previous() }
                    .keyboardShortcut(.leftArrow, modifiers: .command)
            }
        }
        WindowGroup("Video", id: "video", for: VideoPlayRequest.self) { $request in
            if let request {
                VideoPlayerView(request: request, calls: model.calls, cache: model.cache)
                    .environmentObject(model)
                    .frame(minWidth: 640, minHeight: 360)
            }
        }
        .defaultSize(width: 1280, height: 720)
        MenuBarExtra("TakeoutFM", systemImage: "music.note", isInserted: $showMenuBarPlayer) {
            MiniPlayerView()
                .environmentObject(model)
                .environmentObject(model.player)
        }
        .menuBarExtraStyle(.window)
        Settings {
            SettingsView().environmentObject(model)
        }
    }
}

/** The main window: sign-in or the library; videos open in their own window. */
private struct MacRoot: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Group {
            if model.signedIn {
                MainView()
            } else {
                SignInView()
            }
        }
        .environment(\.playVideo, PlayVideoAction { openWindow(id: "video", value: $0) })
    }
}
