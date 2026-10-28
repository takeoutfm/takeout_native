import SwiftUI
import Shared

/** The user's stations by kind; clicking one plays it (a fresh mix, or the live stream). */
struct RadioView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Loaded(load: { try await model.calls.radio() }) { groups in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 28) {
                    ForEach(groups, id: \.kind) { group in
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitle(title(group.kind))
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16)], spacing: 20) {
                                ForEach(group.stations, id: \.id) { StationCard(station: $0) }
                            }
                        }
                    }
                }
                .padding(24)
            }
        }
        .navigationTitle("Radio")
    }

    private func title(_ kind: StationKind) -> String {
        switch kind {
        case .stream: return "Streams"
        case .genre: return "Genres"
        case .period: return "Decades"
        case .artist: return "Artists"
        case .similar: return "Similar"
        case .series: return "Series"
        default: return "Other"
        }
    }
}

struct StationCard: View {
    let station: Station
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Button {
            model.play { try await model.calls.stationQueue(stationId: station.id) }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                Artwork(url: station.imageUri, symbol: "dot.radiowaves.left.and.right")
                    .aspectRatio(1, contentMode: .fit)
                Text(station.name).font(.headline).lineLimit(2)
            }
        }
        .buttonStyle(.plain)
    }
}

/** A titled, horizontally scrolling row of stations; hidden when there are none. */
struct StationRow: View {
    let title: String
    let stations: [Station]

    var body: some View {
        if !stations.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 16) {
                        ForEach(stations, id: \.id) { StationCard(station: $0).frame(width: 150) }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
    }
}
