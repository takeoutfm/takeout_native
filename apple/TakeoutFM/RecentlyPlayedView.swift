import SwiftUI
import Shared

/**
 * The queues played on this device, newest first (clicking one resumes it where it was left),
 * then Stream History (RADIO-5): the songs heard on live streams, as the phone lists them.
 */
struct RecentlyPlayedView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var player: AudioPlayer
    @State private var confirmClear = false

    var body: some View {
        Group {
            if model.recentlyPlayed.isEmpty && model.streamSongs.isEmpty {
                ContentUnavailableView("Nothing Played Yet", systemImage: "clock.arrow.circlepath")
            } else {
                List {
                    if !model.recentlyPlayed.isEmpty {
                        Section("Recently Played") {
                            ForEach(model.recentlyPlayed, id: \.id) { entry in
                                Button { player.play(entry.toQueue()) } label: {
                                    HStack(spacing: 12) {
                                        Artwork(url: entry.coverUri, symbol: "music.note").frame(width: 44, height: 44)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(entry.title).lineLimit(1)
                                            Text(detail(entry)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                        Spacer()
                                        Text(ago(entry.playedAt)).foregroundStyle(.secondary)
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    if !model.streamSongs.isEmpty {
                        Section("Stream History") {
                            ForEach(Array(model.streamSongs.enumerated()), id: \.offset) { _, song in
                                StreamSongRow(song: song)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Recently Played")
        .toolbar {
            Button("Clear") { confirmClear = true }
                .disabled(model.recentlyPlayed.isEmpty && model.streamSongs.isEmpty)
        }
        .confirmationDialog("Clear Recently Played and Stream History on this device?",
                            isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { model.calls.clearHistory() }
        }
    }

    private func detail(_ entry: PlayedQueue) -> String {
        let tracks = entry.items.count == 1 ? "1 track" : "\(entry.items.count) tracks"
        return entry.creator.isEmpty || entry.creator == entry.title ? tracks : "\(entry.creator) · \(tracks)"
    }
}

/** A song heard on a stream: its title, then artist · station · when, with the station's art. */
private struct StreamSongRow: View {
    let song: StreamSong

    var body: some View {
        let parsed = ListenRulesKt.parseStreamTitle(icy: song.title)
        HStack(spacing: 12) {
            Artwork(url: song.imageUri, symbol: "dot.radiowaves.left.and.right").frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(parsed?.second as String? ?? song.title).lineLimit(1)
                Text([parsed?.first as String?, song.station, ago(song.heardAt)]
                        .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .textSelection(.enabled)
    }
}

/** "3 minutes ago", "yesterday", from epoch ms. */
private func ago(_ epochMs: Int64) -> String {
    Date(timeIntervalSince1970: Double(epochMs) / 1000).formatted(.relative(presentation: .named))
}
