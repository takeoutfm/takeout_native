import SwiftUI
import Shared

/**
 * The stations by kind: live stations with what's on each (RADIO-1), then streams; clicking one
 * tunes in. What's on is fetched again as the first of those tracks ends, while shown.
 */
struct RadioView: View {
    @EnvironmentObject private var model: AppModel
    @State private var onNow: RadioNow?

    var body: some View {
        Loaded(load: { try await model.calls.radio() }) { groups in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 28) {
                    ForEach(groups, id: \.kind) { group in
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitle(title(group.kind))
                            if group.kind == .live {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 300), spacing: 12)], spacing: 12) {
                                    ForEach(group.stations, id: \.id) {
                                        LiveStationTile(station: $0, on: onNow?.stations[$0.id])
                                    }
                                }
                            } else {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16)], spacing: 20) {
                                    ForEach(group.stations, id: \.id) { StationCard(station: $0) }
                                }
                            }
                        }
                    }
                }
                .padding(24)
            }
        }
        .navigationTitle("Radio")
        .task { await followOnNow() }
    }

    /// What's on, again when the first of those tracks ends (or a minute after a failure); the
    /// task ends when the page goes.
    private func followOnNow() async {
        while !Task.isCancelled {
            let now = try? await model.calls.radioNow()
            if let now { onNow = now }
            let refreshAt = now.map { Double($0.refreshAtMs) / 1000 } ?? 0
            let wait = refreshAt > 0 ? refreshAt - Date().timeIntervalSince1970 : 60
            try? await Task.sleep(for: .seconds(min(max(wait, 2), 600)))
        }
    }

    private func title(_ kind: StationKind) -> String {
        switch kind {
        case .live: return "Live"
        case .stream: return "Streams"
        default: return "Other"
        }
    }
}

/**
 * A live station on a colour picked from its id, as on the phone: the cover of what's on, the
 * station's name (and the show while one is on) and "Artist – Title".
 */
struct LiveStationTile: View {
    let station: Station
    let on: OnNow_?
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Button {
            model.play { try await model.calls.stationQueue(stationId: station.id) }
        } label: {
            HStack(spacing: 10) {
                Artwork(url: cover, symbol: "dot.radiowaves.left.and.right")
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                VStack(alignment: .leading, spacing: 2) {
                    Text([station.name, on?.show?.name].compactMap { $0 }.joined(separator: " · "))
                        .font(.headline).lineLimit(1)
                    Text(on?.line ?? station.description)
                        .font(.subheadline).lineLimit(2)
                }
                .foregroundStyle(.black.opacity(0.87))
                Spacer(minLength: 0)
            }
            .padding(8)
            .frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
            .background(Self.color(station.id), in: RoundedRectangle(cornerRadius: 8))
            .contentShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
    }

    private var cover: String {
        if let image = on?.track.imageUri, !image.isEmpty { return image }
        if let image = on?.show?.imageUri, !image.isEmpty { return image }
        return station.imageUri
    }

    /// Material 300 tones, as the phone's tiles; dark text reads on all of them.
    private static let colors: [UInt32] = [
        0xE57373, 0xF06292, 0xBA68C8, 0x9575CD, 0x7986CB, 0x64B5F6, 0x4FC3F7, 0x4DD0E1,
        0x4DB6AC, 0x81C784, 0xAED581, 0xDCE775, 0xFFD54F, 0xFFB74D, 0xFF8A65, 0xA1887F,
    ]

    /// The same colour each launch (Swift's hashValue changes per run).
    private static func color(_ id: String) -> Color {
        let hash = id.unicodeScalars.reduce(UInt32(0)) { $0 &* 31 &+ $1.value }
        let rgb = colors[Int(hash % UInt32(colors.count))]
        return Color(red: Double(rgb >> 16 & 0xFF) / 255, green: Double(rgb >> 8 & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

struct StationCard: View {
    let station: Station
    /// What's on, for a live station.
    var on: OnNow_? = nil
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Button {
            model.play { try await model.calls.stationQueue(stationId: station.id) }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                Artwork(url: on?.track.imageUri.nonEmpty ?? station.imageUri, symbol: "dot.radiowaves.left.and.right")
                    .aspectRatio(1, contentMode: .fit)
                Text(station.name).font(.headline).lineLimit(on == nil ? 2 : 1)
                if let on {
                    Text(on.line).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

/**
 * A titled, horizontally scrolling row of stations, live ones with what's on (fetched once);
 * hidden when there are none.
 */
struct StationRow: View {
    let title: String
    let stations: [Station]
    @EnvironmentObject private var model: AppModel
    @State private var onNow: RadioNow?

    var body: some View {
        if !stations.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 16) {
                        ForEach(stations, id: \.id) { StationCard(station: $0, on: onNow?.stations[$0.id]).frame(width: 150) }
                    }
                    .padding(.bottom, 8)
                }
            }
            .task {
                if stations.contains(where: \.live) { onNow = try? await model.calls.radioNow() }
            }
        }
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
