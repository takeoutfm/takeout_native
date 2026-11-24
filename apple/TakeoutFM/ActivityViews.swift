import SwiftUI
import Shared

/**
 * Activity (LIB-14): the server's play stats, as on the phone: most played artists, releases or
 * tracks over a period (Recent, Last Week, This Month, Last Month), each with its play count.
 * An artist or release opens its page; a track plays from there with the rest of the list.
 */
struct ActivityView: View {
    enum Kind: String, CaseIterable, Identifiable {
        case artists = "Artists", releases = "Releases", tracks = "Tracks"
        var id: Self { self }
    }

    @EnvironmentObject private var model: AppModel
    @AppStorage("activityKind") private var kind: Kind = .artists
    @State private var interval: ActivityInterval = .recent

    /// The periods, in the phone's order.
    private static let intervals: [(ActivityInterval, String)] = [
        (.recent, "Recent"), (.lastweek, "Last Week"), (.thismonth, "This Month"), (.lastmonth, "Last Month"),
    ]

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 10) {
                Picker("Show", selection: $kind) {
                    ForEach(Kind.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                Picker("Period", selection: $interval) {
                    ForEach(Self.intervals, id: \.0) { Text($0.1).tag($0.0) }
                }
                .pickerStyle(.segmented)
            }
            .labelsHidden()
            .padding()
            Loaded(id: interval, keepContent: true, load: { try await model.calls.activity(interval: interval) }) { stats in
                list(stats)
            }
        }
        .navigationTitle("Activity")
    }

    /**
     * [stats] without entries the server sends with no name (a release no longer in the
     * library came back as a blank row that opened nothing).
     */
    private static func named(_ stats: ActivityStats) -> ActivityStats {
        ActivityStats(
            artists: stats.artists.filter { !($0.item?.name.isEmpty ?? true) },
            albums: stats.albums.filter { !($0.item?.name.isEmpty ?? true) },
            tracks: stats.tracks.filter { !($0.item?.title.isEmpty ?? true) }
        )
    }

    @ViewBuilder
    private func list(_ all: ActivityStats) -> some View {
        let stats = Self.named(all)
        let empty = switch kind {
        case .artists: stats.artists.isEmpty
        case .releases: stats.albums.isEmpty
        case .tracks: stats.tracks.isEmpty
        }
        if empty {
            ContentUnavailableView("Nothing played", systemImage: "chart.bar",
                                   description: Text("Plays show here when Track Activity is on."))
        } else {
            List {
                switch kind {
                case .artists:
                    ForEach(Array(stats.artists.enumerated()), id: \.offset) { _, counted in
                        if let artist = counted.item {
                            NavigationLink(value: Route.artist(artist.id)) {
                                row(imageUri: artist.imageUri, symbol: "person.fill", title: artist.name, detail: "", count: counted.count)
                            }
                        }
                    }
                case .releases:
                    ForEach(Array(stats.albums.enumerated()), id: \.offset) { _, counted in
                        if let album = counted.item {
                            NavigationLink(value: Route.album(album.id)) {
                                row(imageUri: album.coverUri, symbol: "music.note", title: album.name, detail: album.artist, count: counted.count)
                            }
                        }
                    }
                case .tracks:
                    let tracks = stats.tracks.compactMap(\.item)
                    ForEach(Array(stats.tracks.enumerated()), id: \.offset) { index, counted in
                        if let track = counted.item {
                            Button {
                                model.play { model.calls.tracksQueue(title: "Activity", tracks: tracks, startIndex: Int32(index)) }
                            } label: {
                                row(imageUri: track.coverUri, symbol: "music.note", title: track.title,
                                    detail: [track.artist, track.album].filter { !$0.isEmpty }.joined(separator: " · "),
                                    count: counted.count)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    /** A row: art (none when the server gives none, as for artists here), title, detail, count. */
    private func row(imageUri: String, symbol: String, title: String, detail: String, count: Int32) -> some View {
        HStack(spacing: 12) {
            if !imageUri.isEmpty {
                Artwork(url: imageUri, symbol: symbol).frame(width: 40, height: 40)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                if !detail.isEmpty {
                    Text(detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            Text(count == 1 ? "1 play" : "\(count) plays").monospacedDigit().foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
    }
}
