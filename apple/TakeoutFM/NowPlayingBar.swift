import SwiftUI
import Shared

/** The bar along the bottom of the window while something is queued. */
struct NowPlayingBar: View {
    @EnvironmentObject private var player: AudioPlayer

    var body: some View {
        if let item = player.current {
            HStack(spacing: 14) {
                Artwork(url: item.imageUri).frame(width: 44, height: 44)
                VStack(alignment: .leading) {
                    Text(item.title).font(.headline).lineLimit(1)
                    Text(item.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(width: 240, alignment: .leading)
                Button { player.previous() } label: { Image(systemName: "backward.fill") }
                Button { player.togglePlay() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title2)
                }
                Button { player.next() } label: { Image(systemName: "forward.fill") }
                Slider(
                    value: Binding(get: { player.position }, set: { player.seek(to: $0) }),
                    in: 0...max(player.duration, 1)
                )
                Text(format(player.position) + " / " + format(player.duration))
                    .monospacedDigit().foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.bar)
        }
    }

    private func format(_ seconds: Double) -> String {
        let s = Int(seconds)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}
