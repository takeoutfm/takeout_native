import SwiftUI
import Shared

/** The main window: a sidebar of sections, the section's page, and the Now Playing bar. */
struct MainView: View {
    @EnvironmentObject private var model: AppModel

    enum Section: String, CaseIterable, Identifiable {
        case recentlyAdded = "Recently Added"
        case newReleases = "New Releases"
        var id: String { rawValue }
        var icon: String {
            switch self {
            case .recentlyAdded: return "plus.square.on.square"
            case .newReleases: return "sparkles"
            }
        }
    }

    @State private var section: Section? = .recentlyAdded

    var body: some View {
        NavigationSplitView {
            List(Section.allCases, selection: $section) { s in
                Label(s.rawValue, systemImage: s.icon).tag(s)
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200)
            .toolbar {
                ToolbarItem {
                    Menu {
                        Button("Sign Out") { model.signOut() }
                    } label: {
                        Image(systemName: "person.crop.circle")
                    }
                }
            }
        } detail: {
            NavigationStack {
                switch section ?? .recentlyAdded {
                case .recentlyAdded:
                    AlbumGrid(title: "Recently Added") { try await model.calls.recentlyAdded() }
                case .newReleases:
                    AlbumGrid(title: "New Releases") { try await model.calls.newReleases() }
                }
            }
        }
        .safeAreaInset(edge: .bottom) { NowPlayingBar() }
    }
}
