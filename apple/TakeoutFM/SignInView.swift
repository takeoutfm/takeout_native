import SwiftUI
import Shared

/**
 * Device-code sign-in, as on the watch and TV: enter the server, get a code, approve it at
 * <server>/link on any signed-in device; the app signs in by itself once it's approved.
 */
struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("server") private var server = "https://takeout.fm"
    @State private var code: String?
    @State private var link: String?
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
                Text(code)
                    .font(.system(size: 44, weight: .bold, design: .monospaced))
                    .textSelection(.enabled)
                ProgressView().controlSize(.small)
                Button("Cancel") { stop() }
            } else {
                TextField("Server", text: $server)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 360)
                    .onSubmit(start)
                Button("Sign In", action: start)
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
        let s = CodeSignIn(api: model.core.api, session: model.core.session, library: model.core.library)
        signIn = s
        s.start(
            host: server,
            onCode: { c, l in code = c; link = l },
            onSignedIn: { code = nil; link = nil },
            onFailed: { code = nil; link = nil; failed = true }
        )
    }

    private func stop() {
        signIn?.cancel()
        code = nil
        link = nil
    }
}
