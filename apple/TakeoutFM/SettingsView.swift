import SwiftUI
import Shared

/** TakeoutFM → Settings (⌘,) on the Mac; the Library tab's ⚙︎ on iPhone. */
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("showMenuBarPlayer") private var showMenuBarPlayer = true
    @State private var token = ""
    @State private var connecting = false
    @State private var refused = false
    @State private var confirmingSignOut = false

    var body: some View {
        Form {
            // The sidebar's profile menu shows the account on the Mac and iPad; iPhone has none.
            #if os(iOS)
            Section("Account") {
                LabeledContent("Server", value: model.endpoint)
                if let user = model.serverUser {
                    LabeledContent("Connected as", value: user)
                }
                NavigationLink("Link a Device") { LinkView() }
                Button("Sign Out", role: .destructive) { confirmingSignOut = true }
                    .confirmSignOut($confirmingSignOut, model: model)
            }
            #endif
            Section("Appearance") {
                Picker("Theme", selection: $model.theme) {
                    Text("System").tag(ThemeMode.system)
                    Text("Light").tag(ThemeMode.light)
                    Text("Dark").tag(ThemeMode.dark)
                }
                .pickerStyle(.segmented)
                #if os(macOS)
                Toggle("Show in Menu Bar", isOn: $showMenuBarPlayer)
                #endif
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
                CacheSize()
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
        #if os(macOS)
        .frame(width: 480)
        .fixedSize()
        #endif
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

/**
 * Playback Caching's size (Auto or a fixed one), what it keeps, Clear Cached, and a warning when
 * the viewer's own downloads (never removed by themselves) leave little free; as on the phone.
 */
private struct CacheSize: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var cache: MediaCache

    var body: some View {
        Picker("Cache Size", selection: $model.cacheLimitBytes) {
            ForEach(model.calls.cacheChoices.map { $0.int64Value }, id: \.self) { choice in
                Text(choice == 0 ? "Auto (\(formatBytes(cache.limitBytes(0))))" : formatBytes(choice)).tag(choice)
            }
        }
        Text(cache.usage.cachedBytes > 0
             ? "\(formatBytes(cache.usage.cachedBytes)) used. The least recently played goes first; your downloads are never removed."
             : "The least recently played goes first; your downloads are never removed.")
            .font(.callout)
            .foregroundStyle(.secondary)
        if cache.usage.cachedBytes > 0 {
            Button("Clear Cached (\(formatBytes(cache.usage.cachedBytes)))") { cache.purge(clear: true) }
        }
        if cache.usage.lowSpace {
            Label("Your downloads leave little free space. Remove some in Downloads.", systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        }
    }
}
