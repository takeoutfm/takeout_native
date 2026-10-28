import SwiftUI
import Shared

/** The queues played on this Mac, newest first; clicking one resumes it where it was left. */
struct RecentlyPlayedView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var player: AudioPlayer
    @State private var confirmClear = false

    var body: some View {
        Group {
            if model.recentlyPlayed.isEmpty {
                ContentUnavailableView("Nothing Played Yet", systemImage: "clock.arrow.circlepath")
            } else {
                List(model.recentlyPlayed, id: \.id) { entry in
                    Button { player.play(entry.toQueue()) } label: {
                        HStack(spacing: 12) {
                            Artwork(url: entry.coverUri, symbol: "music.note").frame(width: 44, height: 44)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.title).lineLimit(1)
                                Text(detail(entry)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                            Text(played(entry)).foregroundStyle(.secondary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .navigationTitle("Recently Played")
        .toolbar {
            Button("Clear") { confirmClear = true }
                .disabled(model.recentlyPlayed.isEmpty)
        }
        .confirmationDialog("Clear Recently Played?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) { model.calls.clearRecentlyPlayed() }
        }
    }

    private func detail(_ entry: PlayedQueue) -> String {
        let tracks = entry.items.count == 1 ? "1 track" : "\(entry.items.count) tracks"
        return entry.creator.isEmpty || entry.creator == entry.title ? tracks : "\(entry.creator) · \(tracks)"
    }

    private func played(_ entry: PlayedQueue) -> String {
        let date = Date(timeIntervalSince1970: Double(entry.playedAt) / 1000)
        return date.formatted(.relative(presentation: .named))
    }
}
