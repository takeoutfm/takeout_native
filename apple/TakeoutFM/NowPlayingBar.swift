import SwiftUI
import Shared

/**
 * The bar along the bottom of the window while something is queued. A live stream shows LIVE
 * and its current song (from the stream's ICY title) instead of a seek bar.
 */
struct NowPlayingBar: View {
    @EnvironmentObject private var player: AudioPlayer
    @State private var showQueue = false

    var body: some View {
        if let item = player.current {
            HStack(spacing: 14) {
                Artwork(url: item.imageUri, symbol: "music.note").frame(width: 44, height: 44)
                VStack(alignment: .leading) {
                    Text(player.displayTitle).font(.headline).lineLimit(1)
                    Text(player.displaySubtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(width: 260, alignment: .leading)
                // Podcast episodes' etag is their GUID, not a track's.
                if !item.etag.isEmpty && !item.podcast {
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
                Button { showQueue.toggle() } label: { Image(systemName: "list.bullet") }
                    .help("Queue")
                    .popover(isPresented: $showQueue, arrowEdge: .top) { QueueView() }
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

/** The play queue: what it's playing from, every item with the current one marked; clicking one jumps there. */
struct QueueView: View {
    @EnvironmentObject private var player: AudioPlayer

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Playing from").font(.caption).foregroundStyle(.secondary)
                Text(player.queueTitle).font(.headline).lineLimit(1)
            }
            .padding(12)
            Divider()
            ScrollViewReader { scroller in
                List(Array(player.items.enumerated()), id: \.offset) { index, item in
                    Button { player.skip(to: index) } label: {
                        HStack(spacing: 10) {
                            Group {
                                if index == player.index {
                                    Image(systemName: player.isPlaying ? "speaker.wave.2.fill" : "speaker.fill")
                                        .foregroundStyle(.tint)
                                } else {
                                    Text("\(index + 1)").monospacedDigit().foregroundStyle(.secondary)
                                }
                            }
                            .frame(width: 28, alignment: .trailing)
                            Artwork(url: item.imageUri, symbol: "music.note").frame(width: 32, height: 32)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(item.title).fontWeight(index == player.index ? .semibold : .regular).lineLimit(1)
                                Text(item.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .id(index)
                }
                .onAppear { scroller.scrollTo(player.index, anchor: .center) }
            }
        }
        .frame(width: 380, height: 480)
    }
}
