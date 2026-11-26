import SwiftUI
import Shared

/**
 * The bar along the bottom of the window while something is queued. A live stream shows LIVE
 * and its current song (from the stream's ICY title) instead of a seek bar; a live station's
 * track shows play / pause, LIVE (or Behind live · Jump to live) and the time it has left.
 */
struct NowPlayingBar: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var player: AudioPlayer
    @State private var showQueue = false

    var body: some View {
        if let item = player.current {
            HStack(spacing: 14) {
                // The cover and title open Now Playing.
                Button { model.showNowPlaying = true } label: {
                    HStack(spacing: 14) {
                        Artwork(url: item.imageUri, symbol: "music.note").frame(width: 44, height: 44)
                        VStack(alignment: .leading) {
                            Text(player.displayTitle).font(.headline).lineLimit(1)
                            Text(player.displaySubtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        .frame(width: 260, alignment: .leading)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("Now Playing (⌘L)")
                // Podcast episodes' etag is their GUID, not a track's.
                if !item.etag.isEmpty && !item.podcast {
                    FavoriteButton(etag: item.etag)
                }
                if item.live != nil {
                    Button { player.togglePlay() } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title2)
                    }
                    if player.behindLive {
                        Button("Jump to live") { player.jumpToLive() }
                            .help("Behind live: tune in to what's on now")
                    } else {
                        LiveTag()
                    }
                    WithProgress(progress: player.progress) { p in
                        ProgressView(value: min(p.position, max(p.duration, 1)), total: max(p.duration, 1))
                        Text(p.duration > 0 ? "−" + format(max(p.duration - p.position, 0)) : "")
                            .monospacedDigit().foregroundStyle(.secondary)
                            .lineLimit(1).fixedSize()
                    }
                } else if player.isLive {
                    Button { player.togglePlay() } label: {
                        Image(systemName: player.isPlaying ? "stop.fill" : "play.fill").font(.title2)
                    }
                    LiveTag()
                    Spacer()
                } else {
                    Button { player.previous() } label: { Image(systemName: "backward.fill") }
                    Button { player.togglePlay() } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title2)
                    }
                    Button { player.next() } label: { Image(systemName: "forward.fill") }
                    WithProgress(progress: player.progress) { p in
                        Slider(
                            value: Binding(get: { p.position }, set: { player.seek(to: $0) }),
                            in: 0...max(p.duration, 1)
                        )
                        Text(format(p.position) + " / " + format(p.duration))
                            .monospacedDigit().foregroundStyle(.secondary)
                            .lineLimit(1).fixedSize()
                    }
                }
                RoutePicker()
                Button { showQueue.toggle() } label: { Image(systemName: "list.bullet") }
                    .help("Queue")
                    .popover(isPresented: $showQueue, arrowEdge: .top) { QueueView().frame(width: 380, height: 480) }
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
                if let slot = player.liveSlot {
                    Text("Up next on").font(.caption).foregroundStyle(.secondary)
                    Text(slot.stationName).font(.headline).lineLimit(1)
                } else {
                    Text("Playing from").font(.caption).foregroundStyle(.secondary)
                    Text(player.queueTitle).font(.headline).lineLimit(1)
                }
            }
            .padding(12)
            Divider()
            if player.isLiveRadio {
                liveSchedule
            } else {
                queueList
            }
        }
    }

    /// A live station's queue: the track on now and what's next, not to pick from (RADIO-10).
    private var liveSchedule: some View {
        List(Array(player.items.enumerated().dropFirst(player.index)), id: \.offset) { index, item in
            HStack(spacing: 10) {
                Artwork(url: item.imageUri, symbol: "music.note").frame(width: 32, height: 32)
                VStack(alignment: .leading, spacing: 1) {
                    Text(item.title).fontWeight(index == player.index ? .semibold : .regular).lineLimit(1)
                    Text(item.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                if let slot = item.live {
                    Text(index == player.index ? "Now" : clock(slot.startMs - slot.skewMs))
                        .monospacedDigit().foregroundStyle(.secondary)
                }
            }
        }
    }

    /// When a scheduled track starts, by the device's clock ("9:41 PM").
    private func clock(_ epochMs: Int64) -> String {
        Date(timeIntervalSince1970: Double(epochMs) / 1000).formatted(date: .omitted, time: .shortened)
    }

    private var queueList: some View {
        VStack(spacing: 0) {
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
    }
}

/** The small red LIVE tag of the bars. */
struct LiveTag: View {
    var body: some View {
        Text("LIVE")
            .font(.caption.bold())
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(.red, in: Capsule())
            .foregroundStyle(.white)
    }
}
