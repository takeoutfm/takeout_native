import Shared
import SwiftUI

/**
 * The channels (only when the server has a guide), as the other apps' Live pages: On Now lists
 * what each channel is showing (a click or tap tunes it), and the Guide has a detail panel for
 * the selected program (Watch Live while it's on, Details for its page), a day row to the
 * schedule's horizon and a grid that fetches the guide in windows as it scrolls into them. It
 * all starts over when it gets old. On the Mac the grid takes the keyboard as the TV's remote:
 * ← → by program, ↑ ↓ by channel at the same time, Home to now, Return tunes.
 */
struct LiveView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.playLive) private var playLive
    @Environment(\.compactLayout) private var compact
    @AppStorage("liveGuideGrid") private var grid = false
    @State private var guide: LiveGuideView?
    @State private var failed = false
    @State private var loading = false
    @State private var fetchedAt = Date.distantPast
    /** The server's time, in epoch ms, ticking along. */
    @State private var now = serverNowMs(skewMs: 0)

    var body: some View {
        Group {
            if let guide {
                if guide.channels.isEmpty {
                    ContentUnavailableView("No channels", systemImage: "tv")
                } else if grid {
                    GuideGrid(guide: guide, now: now, ensure: ensure, tune: { playLive($0) })
                } else {
                    onNow(guide)
                }
            } else if failed {
                ContentUnavailableView {
                    Label("Couldn't load the guide", systemImage: "wifi.exclamationmark")
                } actions: {
                    Button("Try Again") { Task { await load() } }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("Live")
        #if os(iOS)
        // the switch in the title's place: no large title over the guide
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: switchPlacement) {
                Picker("View", selection: $grid) {
                    Text("On Now").tag(false)
                    Text("Guide").tag(true)
                }
                .pickerStyle(.segmented)
                .fixedSize()
            }
            ToolbarItem {
                Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise") }
                    .help("Reload")
                    .disabled(loading)
            }
        }
        .task {
            if guide == nil { await load() }
            // the clock, and a fresh guide when it gets old
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                now = serverNowMs(skewMs: guide?.skewMs ?? 0)
                if !loading && Date().timeIntervalSince(fetchedAt) > 30 * 60 { await load() }
            }
        }
    }

    private var switchPlacement: ToolbarItemPlacement {
        #if os(iOS)
        .principal
        #else
        .automatic
        #endif
    }

    private func load() async {
        loading = true
        defer { loading = false }
        if let g = try? await model.calls.guideReload() {
            guide = g
            failed = false
            fetchedAt = Date()
            now = serverNowMs(skewMs: g.skewMs)
        } else if guide == nil {
            failed = true
        }
    }

    /** Fetches the guide from [from] to [until] (epoch ms) where it isn't fetched yet. */
    private func ensure(_ from: Int64, _ until: Int64) {
        guard guide != nil else { return }
        Task {
            if let g = try? await model.calls.guideEnsure(fromMs: from, untilMs: until) { guide = g }
        }
    }

    // ---- On Now ----

    private func onNow(_ guide: LiveGuideView) -> some View {
        List(guide.channels, id: \.name) { channel in
            let program = onAt(channel, now)
            let next = channel.programs.first { $0.startMs > now }
            Button { playLive(channel.name) } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(channel.name).font(.headline).foregroundStyle(Color.accentColor)
                    if let program, !program.block.isEmpty, program.kind != "reserved" {
                        Text(program.block).font(.caption).foregroundStyle(.green)
                    }
                    Text(program.map { p in
                        p.kind == "episode" && !p.subtitle.isEmpty ? "\(programTitle(p)) · \(p.subtitle)" : programTitle(p)
                    } ?? "Break")
                    .font(.title3)
                    .lineLimit(1)
                    if let program {
                        Text("\(clockTime(program.startMs)) – \(clockTime(program.endMs)) · \(timeLeft(program.endMs - now))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        ProgressView(value: Double(now - program.startMs), total: Double(max(program.endMs - program.startMs, 1)))
                    }
                    Text(next.map { "Next: \(clockTime($0.startMs)) · \(programTitle($0))" } ?? "Nothing scheduled yet")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }
}

/** The program on [channel] at [t] (epoch ms), if any. */
private func onAt(_ channel: LiveChannel, _ t: Int64) -> LiveProgram? {
    channel.programs.first { t >= $0.startMs && t < $0.endMs }
}

/** "42 min left", "1 hr 5 min left". */
private func timeLeft(_ ms: Int64) -> String {
    let minutes = max(1, ms / 60_000)
    let h = minutes / 60, m = minutes % 60
    return h == 0 ? "\(m) min left" : m == 0 ? "\(h) hr left" : "\(h) hr \(m) min left"
}

/** Local midnight of an epoch-ms time's day. */
private func midnight(_ ms: Int64) -> Int64 {
    Int64(Calendar.current.startOfDay(for: Date(timeIntervalSince1970: Double(ms) / 1000)).timeIntervalSince1970 * 1000)
}

/** "Today", "Tomorrow", "Sat 14". */
private func dayLabel(_ ms: Int64, now: Int64) -> String {
    let day = Date(timeIntervalSince1970: Double(ms) / 1000)
    let days = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1000)),
                                               to: Calendar.current.startOfDay(for: day)).day ?? 0
    if days == 0 { return "Today" }
    if days == 1 { return "Tomorrow" }
    return day.formatted(.dateTime.weekday(.abbreviated).day())
}

private let minuteMs: Int64 = 60_000
private let halfHourMs: Int64 = 30 * minuteMs
private let hourMs: Int64 = 60 * minuteMs

/** The selected program and its channel. */
private struct Selection: Equatable {
    let channel: String
    let program: LiveProgram
}

/** The guide grid with its detail panel and day row. */
private struct GuideGrid: View {
    let guide: LiveGuideView
    let now: Int64
    let ensure: (Int64, Int64) -> Void
    let tune: (String) -> Void

    @Environment(\.compactLayout) private var compact
    /** The program chosen in the grid; nil: the first channel's on now. */
    @State private var chosen: Selection?
    /**
     * The keyboard's time: ↑ and ↓ land on whatever overlaps it, so focus doesn't wander as
     * cell widths change; ← and → move it to the program they reach.
     */
    @State private var anchor: Int64 = 0
    /** The grid starts at the half hour it opened in and doesn't go back. */
    @State private var start: Int64
    /** How far the grid has scrolled across, in points. */
    /** The half hour at the grid's left edge, as it scrolls; setting it scrolls there. */
    @State private var scrolledTo: Int64?
    @State private var width: CGFloat = 600
    @FocusState private var focused: Bool

    init(guide: LiveGuideView, now: Int64, ensure: @escaping (Int64, Int64) -> Void, tune: @escaping (String) -> Void) {
        self.guide = guide
        self.now = now
        self.ensure = ensure
        self.tune = tune
        // set before the first layout: the ruler and cells are laid out from it
        _start = State(initialValue: now / halfHourMs * halfHourMs)
    }

    private var pointsPerMinute: CGFloat { compact ? 3 : 4 }
    private var nameWidth: CGFloat { compact ? 92 : 128 }
    private var rowHeight: CGFloat { compact ? 52 : 56 }
    private let rulerHeight: CGFloat = 24

    private var end: Int64 {
        max(guide.horizonMs > 0 ? guide.horizonMs : guide.endMs + 6 * hourMs, start + hourMs)
    }
    private var viewStart: Int64 { scrolledTo ?? start }
    private var viewLength: Int64 { Int64(width / pointsPerMinute) * minuteMs }
    private var selection: Selection? {
        chosen ?? guide.channels.lazy.compactMap { c in onAt(c, now).map { Selection(channel: c.name, program: $0) } }.first
    }

    private func x(_ ms: Int64) -> CGFloat { CGFloat(ms - start) / CGFloat(minuteMs) * pointsPerMinute }

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 12) {
                if let selection { detail(selection) }
                days
                HStack(alignment: .top, spacing: 0) {
                    names
                    across
                }
            }
            .padding(.vertical)
        }
        .onAppear {
            anchor = now
            ensure(now, now + 12 * hourMs)
        }
        #if os(macOS)
        .focusable()
        .focused($focused)
        .focusEffectDisabled()
        .onKeyPress(.leftArrow) { moveProgram(-1); return .handled }
        .onKeyPress(.rightArrow) { moveProgram(1); return .handled }
        .onKeyPress(.upArrow) { moveChannel(-1); return .handled }
        .onKeyPress(.downArrow) { moveChannel(1); return .handled }
        .onKeyPress(.return) { open(); return .handled }
        .onKeyPress(.home) { goTo(now); return .handled }
        #endif
    }

    // ---- The detail panel ----

    /**
     * The selected program: the art beside its details, then Watch Live and Details. On a
     * phone the art is small and the buttons go under both, so the grid stays in view (as on
     * Android).
     */
    private func detail(_ selection: Selection) -> some View {
        let p = selection.program
        let on = now >= p.startMs && now < p.endMs
        let subtitle = p.kind == "reserved" ? (p.subtitle.isEmpty ? "Programs announced the day before" : p.subtitle) : p.subtitle
        let info = [
            selection.channel,
            "\(dayLabel(p.startMs, now: now)) \(clockTime(p.startMs)) – \(clockTime(p.endMs))",
            p.rating,
            on ? timeLeft(p.endMs - now) : "",
        ].filter { !$0.isEmpty }.joined(separator: " · ")
        let buttons = FlowRow {
            if on && !p.videoUri.isEmpty {
                Button { tune(selection.channel) } label: { Label("Watch Live", systemImage: "play.fill") }
                    .buttonStyle(.borderedProminent)
            }
            if !p.videoId.isEmpty {
                NavigationLink(value: Route.video(p.videoId)) { Label("Details", systemImage: "info.circle") }
                    .buttonStyle(.bordered)
            }
        }
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: compact ? 12 : 20) {
                Artwork(url: p.backdropUri.isEmpty ? p.imageUri : p.backdropUri, symbol: "tv")
                    .frame(width: compact ? 128 : 224, height: compact ? 72 : 126)
                VStack(alignment: .leading, spacing: compact ? 3 : 6) {
                    if !p.block.isEmpty && p.kind != "reserved" {
                        Text(p.block).font(compact ? .caption : .callout).foregroundStyle(.green)
                    }
                    Text(programTitle(p)).font(compact ? .headline : .title2.bold()).lineLimit(2)
                    if !subtitle.isEmpty {
                        Text(subtitle).font(compact ? .subheadline : .body).lineLimit(compact ? 1 : 2)
                    }
                    Text(info)
                        .font(compact ? .caption : .callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(compact ? 2 : nil)
                    if !compact { buttons }
                }
            }
            if compact { buttons.controlSize(.small) }
        }
        .padding(.horizontal)
    }

    // ---- The days ----

    private var days: some View {
        let last = guide.horizonMs > 0 ? guide.horizonMs : guide.endMs
        var list: [Int64] = []
        var d = midnight(now)
        while d < last && list.count < 14 {
            list.append(d)
            d = midnight(d + 26 * hourMs)  // the next day, across a clock change
        }
        let current = midnight(max(viewStart, now))
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(list, id: \.self) { day in
                    let label = dayLabel(day, now: now)
                    if day == current {
                        Button(label) { goTo(day == midnight(now) ? now : day + (now - midnight(now))) }
                            .buttonStyle(.borderedProminent)
                    } else {
                        // this time of day on that day (today: now)
                        Button(label) { goTo(day == midnight(now) ? now : day + (now - midnight(now))) }
                            .buttonStyle(.bordered)
                    }
                }
            }
            .padding(.horizontal)
        }
    }

    // ---- The grid ----

    private var names: some View {
        VStack(alignment: .leading, spacing: 0) {
            Color.clear.frame(width: nameWidth, height: rulerHeight)
            ForEach(guide.channels, id: \.name) { channel in
                Button { tune(channel.name) } label: {
                    Text(channel.name)
                        .font(.callout.weight(.semibold))
                        .lineLimit(2)
                        .frame(width: nameWidth - 12, height: rowHeight, alignment: .leading)
                        .padding(.leading, 12)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("Watch \(channel.name)")
            }
        }
    }

    private var across: some View {
        ScrollView(.horizontal) {
            ZStack(alignment: .topLeading) {
                // the half hours, as ids to scroll to, and their labels
                LazyHStack(alignment: .top, spacing: 0) {
                    ForEach(Array(stride(from: start, to: end, by: Int(halfHourMs))), id: \.self) { t in
                        Text(midnight(t) == t ? dayLabel(t, now: now) : clockTime(t))
                            .font(.caption.weight(midnight(t) == t ? .bold : .regular))
                            .foregroundStyle(.secondary)
                            .padding(.leading, 4)
                            .frame(width: x(t + halfHourMs) - x(t), height: rulerHeight, alignment: .leading)
                            .id(t)
                    }
                }
                .scrollTargetLayout()
                .frame(height: rulerHeight)
                ForEach(Array(guide.channels.enumerated()), id: \.element.name) { index, channel in
                    row(channel).offset(y: rulerHeight + CGFloat(index) * rowHeight)
                }
                // now
                if now >= start {
                    Rectangle()
                        .fill(Color.accentColor)
                        .frame(width: 2, height: rulerHeight + rowHeight * CGFloat(guide.channels.count))
                        .offset(x: x(now))
                }
            }
            .frame(width: x(end), height: rulerHeight + rowHeight * CGFloat(guide.channels.count), alignment: .topLeading)
        }
        .scrollPosition(id: $scrolledTo, anchor: .leading)
        .coordinateSpace(name: "across")
        .background(GeometryReader { g in
            Color.clear.onAppear { width = g.size.width }.onChange(of: g.size.width) { width = g.size.width }
        })
        // fetch what's coming into view (and six hours on), once per half hour moved
        .onChange(of: scrolledTo) { ensure(viewStart, viewStart + viewLength + 6 * hourMs) }
    }

    private func row(_ channel: LiveChannel) -> some View {
        ZStack(alignment: .topLeading) {
            ForEach(channel.music.filter { $0.endMs > start }, id: \.startMs) { m in
                cell(from: m.startMs, to: m.endMs, heading: "Music", detail: clockTime(m.startMs), special: false, dim: true, selected: false)
            }
            // all of it: a week of guide is a few hundred cells
            ForEach(channel.programs.filter { $0.endMs > start }, id: \.startMs) { p in
                let selected = selection?.channel == channel.name && selection?.program.startMs == p.startMs
                let detail = p.kind == "reserved" || p.kind == "episode" ? p.subtitle : !p.block.isEmpty ? p.block : clockTime(p.startMs)
                cell(from: p.startMs, to: p.endMs, heading: programTitle(p), detail: detail,
                     special: !p.block.isEmpty, dim: p.kind == "offair" || p.endMs <= now, selected: selected)
                    .onTapGesture(count: 2) {
                        if now >= p.startMs && now < p.endMs { tune(channel.name) }
                    }
                    .onTapGesture {
                        chosen = Selection(channel: channel.name, program: p)
                        anchor = max(p.startMs, now)
                        focused = true
                    }
                    .help(programTitle(p))
            }
        }
        .frame(width: x(end), height: rowHeight, alignment: .topLeading)
    }

    /**
     * A cell; one that began before the view's left edge keeps its title at that edge (as on
     * Android), up to 15 minutes before it ends. Each cell measures where it is in the scroll
     * view, so the title follows the scrolling exactly.
     */
    private func cell(from: Int64, to: Int64, heading: String, detail: String, special: Bool, dim: Bool, selected: Bool) -> some View {
        let left = x(max(from, start))
        let w = max(x(to) - left - 2, 0)
        let limit = max(0, w - 15 * pointsPerMinute)
        return GeometryReader { g in
            let scrolledOff = -g.frame(in: .named("across")).minX
            let shift = min(max(0, scrolledOff), limit)
            let continued = from < start || shift > 0
            VStack(alignment: .leading, spacing: 1) {
                Text((continued ? "‹ " : "") + heading).font(.callout.bold()).lineLimit(1)
                if !detail.isEmpty {
                    Text(detail).font(.caption).opacity(0.7).lineLimit(1)
                }
            }
            .foregroundStyle(selected ? Color.white : Color.primary)
            .opacity(dim && !selected ? 0.6 : 1)
            .padding(.leading, 8 + shift)
            .padding(.trailing, 4)
            .frame(width: g.size.width, height: g.size.height, alignment: .leading)
        }
        .frame(width: w, height: rowHeight - 4)
        .background(
            RoundedRectangle(cornerRadius: 6).fill(
                selected ? Color.accentColor
                    : special ? Color.accentColor.opacity(0.25)
                    : Color.primary.opacity(dim ? 0.05 : 0.1)
            )
        )
        .contentShape(Rectangle())
        .offset(x: left, y: 2)
    }

    // ---- Moving around (the keyboard, the day row) ----

    private func channelIndex() -> Int {
        guide.channels.firstIndex { $0.name == selection?.channel } ?? 0
    }

    /** On [channel] at [t]: the program on, else the next to start, else the last. */
    private func focusAt(_ channel: LiveChannel, _ t: Int64) -> LiveProgram? {
        let ps = channel.programs
        return ps.first { t >= $0.startMs && t < $0.endMs } ?? ps.first { $0.startMs > t } ?? ps.last { $0.endMs <= t }
    }

    /** Scrolls the grid to the half hour with [t] in it. */
    private func scroll(to t: Int64) {
        withAnimation { scrolledTo = max(start, t / halfHourMs * halfHourMs) }
    }

    /** Scrolls so [p]'s start (or now, if it's on) is in view. */
    private func reveal(_ p: LiveProgram) {
        let from = max(p.startMs, now)
        if from < viewStart {
            scroll(to: from)
        } else if from >= viewStart + viewLength - halfHourMs {
            scroll(to: from - halfHourMs)
        }
    }

    private func moveProgram(_ delta: Int) {
        let channels = guide.channels
        guard channels.indices.contains(channelIndex()), let p = selection?.program else { return }
        let channel = channels[channelIndex()]
        let to = delta > 0 ? channel.programs.first { $0.startMs > p.startMs }
            // the guide doesn't go back in time
            : channel.programs.last { $0.startMs < p.startMs && $0.endMs > now }
        guard let to else {
            // nothing fetched past the edge yet; it's on its way
            if delta > 0 { ensure(p.endMs, p.endMs + 6 * hourMs) }
            return
        }
        chosen = Selection(channel: channel.name, program: to)
        anchor = max(to.startMs, now)
        reveal(to)
    }

    private func moveChannel(_ delta: Int) {
        let i = channelIndex() + delta
        guard guide.channels.indices.contains(i), let p = focusAt(guide.channels[i], max(anchor, now)) else { return }
        chosen = Selection(channel: guide.channels[i].name, program: p)
        // as most guides: the time across stays put, unless the program is out of view
        if p.endMs <= viewStart || p.startMs >= viewStart + viewLength { reveal(p) }
    }

    /** To [t] on the selected channel: the view there, and the program at it. */
    private func goTo(_ t: Int64) {
        anchor = max(t, now)
        scroll(to: anchor)
        ensure(anchor, anchor + viewLength + 6 * hourMs)
        if guide.channels.indices.contains(channelIndex()) {
            let channel = guide.channels[channelIndex()]
            if let p = focusAt(channel, anchor) { chosen = Selection(channel: channel.name, program: p) }
        }
    }

    /** Return: tunes the selected program while it's on. */
    private func open() {
        guard let s = selection, now >= s.program.startMs && now < s.program.endMs else { return }
        tune(s.channel)
    }
}
