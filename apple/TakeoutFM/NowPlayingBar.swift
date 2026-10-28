import SwiftUI
import Shared

/**
 * The bar along the bottom of the window while something is queued. A live stream shows LIVE
 * and its current song (from the stream's ICY title) instead of a seek bar.
 */
struct NowPlayingBar: View {
    @EnvironmentObject private var player: AudioPlayer

    var body: some View {
        if let item = player.current {
            HStack(spacing: 14) {
                Artwork(url: item.imageUri, symbol: "music.note").frame(width: 44, height: 44)
                VStack(alignment: .leading) {
                    Text(title(item)).font(.headline).lineLimit(1)
                    Text(subtitle(item)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(width: 260, alignment: .leading)
                if !item.etag.isEmpty {
                    FavoriteButton(etag: item.etag)
                }
                if player.isLive {
                    Button { player.togglePlay() } label: {
                        Image(systemName: player.isPlaying ? "stop.fill" : "play.fill").font(.title2)
                    }
                    Text("LIVE")
                        .font(.caption.bold())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.red, in: Capsule())
                        .foregroundStyle(.white)
                    Spacer()
                } else {
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
            }
            .buttonStyle(.borderless)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.bar)
        }
    }

    /// A stream's current song when it names one ("Artist - Title"), else the item's title.
    private func title(_ item: QueueItem) -> String {
        guard let icy = player.streamTitle else { return item.title }
        return AudioPlayer.split(icy)?.title ?? icy
    }

    private func subtitle(_ item: QueueItem) -> String {
        guard let icy = player.streamTitle else { return item.artist }
        guard let artist = AudioPlayer.split(icy)?.artist else { return item.title }
        return "\(artist) · \(item.title)"
    }

    private func format(_ seconds: Double) -> String {
        let s = Int(seconds)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}
