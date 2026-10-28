import SwiftUI

/** TakeoutFM → Settings (⌘,). */
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Form {
            Section {
                Toggle("Track Activity", isOn: $model.reportPlays)
                Text("Tells your Takeout server what you listen to (half a track, or 4 minutes), for its Activity and stats.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Music")
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .fixedSize()
    }
}
