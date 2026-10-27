import SwiftUI
import Shared

@main
struct TakeoutFMApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if model.signedIn {
                    MainView()
                } else {
                    SignInView()
                }
            }
            .environmentObject(model)
            .environmentObject(model.player)
            .frame(minWidth: 900, minHeight: 600)
        }
        .commands {
            CommandMenu("Playback") {
                Button("Play/Pause") { model.player.togglePlay() }
                    .keyboardShortcut(.space, modifiers: [])
                Button("Next") { model.player.next() }
                    .keyboardShortcut(.rightArrow, modifiers: .command)
                Button("Previous") { model.player.previous() }
                    .keyboardShortcut(.leftArrow, modifiers: .command)
            }
        }
    }
}

/** The shared Kotlin core and the app-wide state, created once at launch. */
@MainActor
final class AppModel: ObservableObject {
    let core: TakeoutCore
    let calls: SwiftCalls
    let player: AudioPlayer
    @Published private(set) var signedIn = false
    private var sessionWatch: Watching?

    init() {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev"
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        // The database lives in Application Support (the sandbox container's), created if needed.
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TakeoutFM", isDirectory: true)
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        core = TakeoutCore(
            secureStore: KeychainStore(service: "com.takeoutfm.mac"),
            userAgent: "TakeoutFM-Mac/\(version) (takeoutfm.com; macOS \(os))",
            dataDirectory: support.path
        )
        calls = SwiftCalls(core: core)
        player = AudioPlayer(calls: calls)
        signedIn = calls.isSignedIn
        sessionWatch = WatchKt.watch(flow: core.session.state) { [weak self] state in
            self?.signedIn = (state as? SessionState)?.isLoggedIn ?? false
        }
        Task { try? await calls.refreshLibrary() }
    }

    func signOut() {
        player.stop()
        core.session.signOut()
    }
}
