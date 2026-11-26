import SwiftUI
import Shared

/**
 * Now Playing, as on the phone and TV: a large cover over a gradient of its colour (and the
 * artist's background); title, artist and album; the seek bar; previous · −10 s · play/pause ·
 * +30 s · next; repeat, ♥, AirPlay and the queue (a column beside it when the window is wide).
 * Swiping the cover skips. A live stream shows LIVE, the song on air (its ICY title) and
 * play/stop only. A live station's track shows the station where the album would be, LIVE (or
 * Behind live · Jump to live), its progress and time left, play / pause, ♥ and what's next. On the iPhone it's a card (swiped down, or the chevron, to close); on the Mac
 * and iPad a sidebar page ([asPage]), where Esc goes back to the page it was opened from.
 */
struct NowPlayingView: View {
    var asPage = false
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var player: AudioPlayer
    @State private var showQueue = false
    @State private var scrub: Double?
    /// Bumped on each play/pause press, for its haptic (not on buffering changes).
    @State private var presses = 0

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // The backdrop runs under the safe areas (on iPhone, past the card's bottom edge).
                Group {
                    Rectangle().fill(.background)
                    if let color = player.artwork?.color {
                        LinearGradient(colors: [color.opacity(0.6), color.opacity(0.12)], startPoint: .top, endPoint: .bottom)
                            .animation(.easeInOut, value: player.artwork?.url)
                    }
                    if let background = player.current?.backgroundUri, !background.isEmpty {
                        // Faded and only lightly blurred: the photo keeps its detail (radius 12
                        // left just a wash), while the text over it stays readable.
                        Artwork(url: background)
                            .opacity(0.35)
                            .blur(radius: 3)
                    }
                }
                .ignoresSafeArea()
                if let item = player.current {
                    let wide = geo.size.width > 1100
                    let cover = Artwork(url: item.imageUri, symbol: "music.note")
                        .aspectRatio(1, contentMode: .fit)
                        .frame(maxWidth: 520, maxHeight: 520)
                        .shadow(radius: 24, y: 8)
                        // Swipe the cover: left for the next track, right for the previous.
                        .gesture(DragGesture(minimumDistance: 30).onEnded { drag in
                            guard !player.isLive, !player.isLiveRadio, abs(drag.translation.width) > abs(drag.translation.height) else { return }
                            if drag.translation.width < -60 { player.next() }
                            if drag.translation.width > 60 { player.previous() }
                        })
                    if geo.size.height > geo.size.width {
                        // Upright (a phone): the cover above the details.
                        VStack(spacing: 28) {
                            cover
                            details(item, showQueueButton: true)
                        }
                        .padding(.horizontal, 24)
                        .padding(.top, 56)
                    } else {
                        HStack(alignment: .center, spacing: 40) {
                            cover
                            details(item, showQueueButton: !wide)
                                .frame(maxWidth: 460)
                            if wide {
                                QueueView()
                                    .frame(width: 360)
                                    .frame(maxHeight: 560)
                                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                            }
                        }
                        .padding(48)
                    }
                } else {
                    ContentUnavailableView("Nothing Playing", systemImage: "music.note",
                                           description: Text("Play an album, a mix, a station or a podcast."))
                }
            }
            .overlay(alignment: .topLeading) {
                Button { model.showNowPlaying = false } label: {
                    Image(systemName: "chevron.down").font(.title2)
                }
                .buttonStyle(.borderless)
                .keyboardShortcut(.cancelAction)
                .help("Close Now Playing (Esc)")
                .padding(20)
                // a page has the sidebar to leave by; Esc still goes back
                .opacity(asPage ? 0 : 1)
                .accessibilityHidden(asPage)
            }
        }
    }

    @ViewBuilder
    private func details(_ item: QueueItem, showQueueButton: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            // One line, sliding sideways when it's too long, so the cover keeps its size and
            // the controls stay put from track to track.
            MarqueeText(text: player.displayTitle, font: .largeTitle.bold())
            Text(player.streamTitle == nil ? item.artist : player.displaySubtitle).font(.title2).lineLimit(1)
            if let slot = item.live {
                Text(slot.label).font(.title3).foregroundStyle(.secondary).lineLimit(1)
            } else if !player.isLive && !item.album.isEmpty && item.album != item.artist {
                Text(item.album).font(.title3).foregroundStyle(.secondary).lineLimit(1)
            }
            if item.live != nil {
                LiveRadioState().padding(.top, 16)
                WithProgress(progress: player.progress) { p in
                    LiveRadioProgress(position: p.position, duration: p.duration)
                }
                .padding(.vertical, 12)
                HStack {
                    FavoriteButton(etag: item.etag).font(.title2)
                    Spacer()
                    playPause
                    Spacer()
                    RoutePicker()
                    if showQueueButton { queueButton }
                }
                if player.items.indices.contains(player.index + 1) {
                    let next = player.items[player.index + 1]
                    Text("Next: \(artistTitle(next))")
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .padding(.top, 8)
                }
            } else if player.isLive {
                LiveBadge(playing: player.isPlaying).padding(.vertical, 16)
                HStack {
                    playPause
                    Spacer()
                    RoutePicker()
                    if showQueueButton { queueButton }
                }
            } else {
                seekBar.padding(.top, 20)
                HStack {
                    Button { player.previous() } label: { Image(systemName: "backward.fill").font(.title) }
                        .help("Previous")
                    Spacer()
                    Button { player.seek(to: max(player.position - 10, 0)) } label: { Image(systemName: "gobackward.10").font(.title) }
                        .help("Back 10 seconds")
                    Spacer()
                    playPause
                    Spacer()
                    Button { player.seek(to: player.position + 30) } label: { Image(systemName: "goforward.30").font(.title) }
                        .help("Forward 30 seconds")
                    Spacer()
                    Button { player.next() } label: { Image(systemName: "forward.fill").font(.title) }
                        .help("Next")
                }
                HStack(spacing: 24) {
                    Button { player.cycleRepeat() } label: {
                        Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                            .foregroundStyle(player.repeatMode == .off ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint))
                    }
                    .help(repeatHelp)
                    if !item.etag.isEmpty && !item.podcast {
                        FavoriteButton(etag: item.etag)
                    }
                    Spacer()
                    RoutePicker()
                    if showQueueButton { queueButton }
                }
                .font(.title2)
                .padding(.top, 12)
            }
        }
        .buttonStyle(.borderless)
    }

    private var playPause: some View {
        Button { presses += 1; player.togglePlay() } label: {
            Image(systemName: player.isPlaying ? (player.isLive ? "stop.fill" : "pause.fill") : "play.fill")
                .font(.system(size: 30))
                .frame(width: 72, height: 72)
                .background(.tint, in: Circle())
                .foregroundStyle(.white)
        }
        .buttonStyle(.plain)  // Space is the Playback menu's Play/Pause
        .sensoryFeedback(.impact(weight: .light), trigger: presses)
    }

    private var queueButton: some View {
        Button { showQueue.toggle() } label: { Image(systemName: "list.bullet").font(.title2) }
            .help("Queue")
            .popover(isPresented: $showQueue, arrowEdge: .top) { QueueView().frame(width: 380, height: 480) }
    }

    /// Dragging previews the time; letting go seeks.
    private var seekBar: some View {
        WithProgress(progress: player.progress) { p in
            seekBar(p)
        }
    }

    private func seekBar(_ p: PlaybackProgress) -> some View {
        VStack(spacing: 4) {
            Slider(
                value: Binding(get: { scrub ?? p.position }, set: { scrub = $0 }),
                in: 0...max(p.duration, 1),
                onEditingChanged: { editing in
                    if !editing, let s = scrub {
                        player.seek(to: s)
                        scrub = nil
                    }
                }
            )
            .disabled(p.duration <= 0)
            HStack {
                Text(time(scrub ?? p.position))
                Spacer()
                Text(p.duration > 0 ? time(p.duration) : "")
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
    }

    private var repeatHelp: String {
        switch player.repeatMode {
        case .off: return "Repeat: Off"
        case .all: return "Repeat: All"
        case .one: return "Repeat: One"
        }
    }

    private func time(_ seconds: Double) -> String {
        let s = Int(seconds)
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }
}

/** The red LIVE pill; its dot is green while the stream plays (as on the phone and TV). */
struct LiveBadge: View {
    let playing: Bool

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(playing ? Color(red: 0.41, green: 0.94, blue: 0.68) : .gray).frame(width: 10, height: 10)
            Text("LIVE").bold().foregroundStyle(.white)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .background(Color(red: 0.83, green: 0.18, blue: 0.18), in: Capsule())
    }
}

/** LIVE, or "Behind live" with a button to jump to live, as a live station plays (RADIO-12). */
struct LiveRadioState: View {
    @EnvironmentObject private var player: AudioPlayer

    var body: some View {
        if player.behindLive {
            HStack(spacing: 8) {
                Text("Behind live").foregroundStyle(.secondary)
                Button("Jump to live") { player.jumpToLive() }
            }
        } else {
            LiveBadge(playing: player.isPlaying)
        }
    }
}

/** A live track's progress, which can't be dragged, and the time it has left (RADIO-10). */
struct LiveRadioProgress: View {
    let position: Double
    let duration: Double

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            ProgressView(value: min(position, max(duration, 1)), total: max(duration, 1))
            Text(duration > 0 ? "−" + timeText(max(duration - position, 0)) : "")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }
}

/** "Artist – Title", or the title alone. */
func artistTitle(_ item: QueueItem) -> String {
    item.artist.isEmpty ? item.title : "\(item.artist) – \(item.title)"
}

/** "3:05", or "1:02:03". */
func timeText(_ seconds: Double) -> String {
    let s = Int(seconds)
    return s >= 3600
        ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        : String(format: "%d:%02d", s / 60, s % 60)
}
