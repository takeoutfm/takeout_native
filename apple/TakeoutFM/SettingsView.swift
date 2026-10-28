import SwiftUI
import Shared

/** TakeoutFM → Settings (⌘,). */
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("showMenuBarPlayer") private var showMenuBarPlayer = true
    @State private var token = ""
    @State private var connecting = false
    @State private var refused = false

    var body: some View {
        Form {
            Section("Appearance") {
                Picker("Theme", selection: $model.theme) {
                    Text("System").tag(ThemeMode.system)
                    Text("Light").tag(ThemeMode.light)
                    Text("Dark").tag(ThemeMode.dark)
                }
                .pickerStyle(.segmented)
                Toggle("Show in Menu Bar", isOn: $showMenuBarPlayer)
            }
            Section("Music") {
                Toggle("Track Activity", isOn: $model.reportPlays)
                Text("Tells your Takeout server what you listen to (half a track, or 4 minutes), for its Activity and stats.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Toggle("Playback Caching", isOn: $model.playbackCaching)
                Text("Keeps tracks and episodes you play to the end, so they play offline (they're listed in Downloads). Each is fetched once more to keep it; radio streams aren't kept.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Section("ListenBrainz") {
                if let user = model.listenBrainzUser {
                    LabeledContent("Connected as", value: user)
                    Toggle("Submit Listens", isOn: $model.submitListens)
                    Button("Disconnect", role: .destructive) { model.calls.disconnectListenBrainz() }
                } else {
                    SecureField("User token", text: $token)
                        .onSubmit(connect)
                    HStack {
                        Link("Get your token", destination: URL(string: "https://listenbrainz.org/settings/")!)
                        Spacer()
                        if connecting { ProgressView().controlSize(.small) }
                        Button("Connect", action: connect)
                            .disabled(token.trimmingCharacters(in: .whitespaces).isEmpty || connecting)
                    }
                    if refused {
                        Text("ListenBrainz didn't accept that token.").foregroundStyle(.red)
                    }
                }
                Text("Sends what you play, including songs heard on radio streams, to your ListenBrainz profile.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 480)
        .fixedSize()
    }

    private func connect() {
        let value = token
        connecting = true
        refused = false
        Task {
            defer { connecting = false }
            do {
                if try await model.calls.connectListenBrainz(token: value) == nil {
                    refused = true
                } else {
                    token = ""
                }
            } catch {
                NSLog("ListenBrainz connect failed: \(error)")
                refused = true
            }
        }
    }
}
