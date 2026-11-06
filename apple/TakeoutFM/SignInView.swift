import SwiftUI
import Shared

/**
 * Device-code sign-in, as on the watch and TV: enter the server, get a code, approve it at
 * <server>/link on any signed-in device; the app signs in by itself once it's approved. The code
 * shows the time it has left; once it expires it stays, struck out, with a button for a new one.
 */
struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("server") private var server = "https://takeout.fm"
    @State private var code: String?
    @State private var link: String?
    @State private var expiresAt = Date.distantFuture
    @State private var expired = false
    @State private var failed = false
    @State private var signIn: CodeSignIn?

    var body: some View {
        VStack(spacing: 20) {
            Text("TakeoutFM").font(.largeTitle.bold())
            if let code, let link {
                Text("Go to").foregroundStyle(.secondary)
                Link(link, destination: URL(string: link) ?? URL(string: "https://takeout.fm")!)
                    .font(.title3)
                Text("and enter").foregroundStyle(.secondary)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let left = max(0, Int(expiresAt.timeIntervalSince(context.date).rounded(.up)))
                    let gone = expired || left == 0
                    VStack(spacing: 12) {
                        Text(code)
                            .font(.system(size: 44, weight: .bold, design: .monospaced))
                            .strikethrough(gone)
                            .opacity(gone ? 0.38 : 1)
                            .textSelection(.enabled)
                        if gone {
                            Text("This code has expired.").foregroundStyle(.red)
                            Button("Get a New Code", action: start)
                                .buttonStyle(.borderedProminent)
                                .keyboardShortcut(.defaultAction)
                            Button("Cancel") { stop() }
                                .buttonStyle(.bordered)
                        } else {
                            HStack(spacing: 8) {
                                ProgressView().controlSize(.small)
                                Text("Waiting for approval · expires in \(String(format: "%d:%02d", left / 60, left % 60))")
                                    .foregroundStyle(.secondary)
                                    .monospacedDigit()
                            }
                            Button("Cancel") { stop() }
                                .buttonStyle(.bordered)
                        }
                    }
                }
            } else {
                TextField("Server", text: $server)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 360)
                    .onSubmit(start)
                Button("Sign In", action: start)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                if failed {
                    Text("Couldn't sign in. Check the server and try again.")
                        .foregroundStyle(.red)
                }
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onDisappear { signIn?.cancel() }
    }

    private func start() {
        failed = false
        signIn?.cancel()
        let s = CodeSignIn(api: model.core.api, session: model.core.session, library: model.core.library)
        signIn = s
        s.start(
            host: server,
            onCode: { c, l, expiresIn in
                code = c
                link = l
                expiresAt = Date().addingTimeInterval(TimeInterval(expiresIn.int32Value))
                expired = false
            },
            onSignedIn: { code = nil; link = nil },
            onExpired: { expired = true },
            onFailed: { code = nil; link = nil; failed = true }
        )
    }

    private func stop() {
        signIn?.cancel()
        code = nil
        link = nil
        expired = false
    }
}
