import AVFoundation
import SwiftUI
import Shared

@main
struct TakeoutFMiOSApp: App {
    @StateObject private var model = AppModel()

    init() {
        // Music plays on in the background and on the lock screen (UIBackgroundModes audio).
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
    }

    var body: some Scene {
        WindowGroup {
            IOSRoot()
                .environmentObject(model)
                .environmentObject(model.player)
                .environmentObject(model.cache)
                .preferredColorScheme(model.colorScheme)
        }
    }
}

/**
 * Sign-in, then the iPhone's tabs or, with room for it (iPad), the Mac's sidebar layout.
 * Videos play in a full-screen player.
 */
private struct IOSRoot: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var video: VideoPlayRequest?

    var body: some View {
        Group {
            if !model.signedIn {
                SignInView()
            } else if sizeClass == .regular {
                MainView()
            } else {
                PhoneTabs()
            }
        }
        .environment(\.playVideo, PlayVideoAction { video = $0 })
        .environment(\.compactLayout, sizeClass == .compact)
        #if DEBUG
        // For testing video without the UI: `-playVideo e-57 -noProgress YES` (as on the Mac).
        .onAppear {
            if let id = UserDefaults.standard.string(forKey: "playVideo") { video = VideoPlayRequest(id: id) }
        }
        #endif
        .fullScreenCover(item: $video) { request in
            VideoPlayerScreen(request: request, calls: model.calls, cache: model.cache)
                .environmentObject(model)
        }
    }
}

extension VideoPlayRequest: Identifiable {}
