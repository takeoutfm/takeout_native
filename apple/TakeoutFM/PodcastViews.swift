import SwiftUI
import Shared

// Podcasts "ps-", episodes "pe-". Episodes play in the music player and resume where they were
// left (progress keyed by the episode's GUID, synced like video). Kotlin's `description` is
// `description_` here.

/** Subscribed podcasts, or every podcast on the server. */
struct PodcastsView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("podcastsShowAll") private var showAll = false

    var body: some View {
        Loaded(id: showAll, load: { try await model.calls.podcasts(subscribedOnly: !showAll) }) { series in
            if series.isEmpty {
                ContentUnavailableView {
                    Label(showAll ? "No Podcasts" : "No Subscriptions", systemImage: "antenna.radiowaves.left.and.right")
                } actions: {
                    if !showAll { Button("Show All Podcasts") { showAll = true } }
                }
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 20)], spacing: 24) {
                        ForEach(series, id: \.id) { s in
                            NavigationLink(value: Route.podcast(s.id)) { PodcastCard(series: s) }
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(20)
                }
            }
        }
        .navigationTitle("Podcasts")
        .toolbar {
            Picker("Show", selection: $showAll) {
                Text("Subscribed").tag(false)
                Text("All").tag(true)
            }
            .pickerStyle(.segmented)
        }
    }
}

struct PodcastCard: View {
    let series: PodcastSeries

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Artwork(url: series.imageUri, symbol: "antenna.radiowaves.left.and.right")
                .aspectRatio(1, contentMode: .fit)
            Text(series.title).font(.headline).lineLimit(1)
            Text(series.author).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

/** A podcast: Subscribe, and its episodes newest first with where you are in each. */
struct PodcastView: View {
    let seriesId: String
    @EnvironmentObject private var model: AppModel
    /// Bumped to reload after marking an episode.
    @State private var version = 0

    var body: some View {
        Loaded(id: "\(seriesId)/\(version)", load: { try await model.calls.podcastDetails(seriesId: seriesId) }) { details in
            PodcastPage(details: details) { version += 1 }
        }
    }
}

private struct PodcastPage: View {
    let details: PodcastDetails
    let changed: () -> Void
    @EnvironmentObject private var model: AppModel
    @State private var subscribed: Bool

    init(details: PodcastDetails, changed: @escaping () -> Void) {
        self.details = details
        self.changed = changed
        _subscribed = State(initialValue: details.subscribed)
    }

    var body: some View {
        List {
            HStack(alignment: .top, spacing: 20) {
                Artwork(url: details.series.imageUri, symbol: "antenna.radiowaves.left.and.right")
                    .frame(width: 180, height: 180)
                VStack(alignment: .leading, spacing: 8) {
                    Text(details.series.title).font(.largeTitle.bold())
                    Text(details.series.author).font(.title3).foregroundStyle(.secondary)
                    Text(details.series.description_).lineLimit(5).foregroundStyle(.secondary)
                    HStack {
                        Button { play(0) } label: { Label("Play Latest", systemImage: "play.fill") }
                            .buttonStyle(.borderedProminent)
                            .disabled(details.episodes.isEmpty)
                        Button(subscribed ? "Unsubscribe" : "Subscribe", action: toggleSubscribed)
                    }
                    .padding(.top, 4)
                }
            }
            .padding(.vertical, 12)
            ForEach(Array(details.episodes.enumerated()), id: \.offset) { index, episode in
                HStack(alignment: .top) {
                    NavigationLink(value: Route.podcastEpisode(seriesId: details.series.id, episodeId: episode.id)) {
                        EpisodeRow(episode: episode).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Button { play(index) } label: { Image(systemName: "play.circle").font(.title) }
                        .buttonStyle(.borderless)
                        .help("Play")
                }
                .contextMenu {
                    Button("Play") { play(index) }
                    let played = episode.progress is EpisodeProgressComplete
                    Button(played ? "Mark as Not Played" : "Mark as Played") { mark(episode, played: !played) }
                }
            }
        }
        .navigationTitle(details.series.title)
    }

    private func play(_ index: Int) {
        let d = details
        model.play {
            try await model.calls.episodesQueue(title: d.series.title, imageUri: d.series.imageUri, episodes: d.episodes, startIndex: Int32(index))
        }
    }

    private func mark(_ episode: PodcastEpisode, played: Bool) {
        Task {
            do {
                try await model.calls.markPlayed(eid: episode.eid, played: played)
                changed()
            } catch {
                NSLog("Mark played failed: \(error)")
            }
        }
    }

    private func toggleSubscribed() {
        let wanted = !subscribed
        subscribed = wanted
        Task {
            do {
                try await model.calls.setSubscribed(seriesId: details.series.id, subscribed: wanted)
            } catch {
                NSLog("Subscribe failed: \(error)")
                subscribed = !wanted
            }
        }
    }
}

private struct EpisodeRow: View {
    let episode: PodcastEpisode

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(episode.title).font(.headline).lineLimit(1)
                Spacer()
                status
                DownloadBadge(etag: episode.eid)
            }
            Text(date).font(.subheadline).foregroundStyle(.secondary)
            Text(ShowNotes.plain(episode.description_)).foregroundStyle(.secondary).lineLimit(2)
        }
        .padding(.vertical, 4)
    }

    private var status: some View { EpisodeStatus(progress: episode.progress) }
    private var date: String { published(episode.date) }
}

/** "12 min left" with a bar, "Played", or nothing when not started. */
private struct EpisodeStatus: View {
    let progress: EpisodeProgress

    var body: some View {
        if let started = progress as? EpisodeProgressStarted {
            HStack(spacing: 6) {
                ProgressView(value: Double(started.fraction)).frame(width: 60)
                Text("\(started.minutesLeft) min left").foregroundStyle(.secondary)
            }
        } else if progress is EpisodeProgressComplete {
            Label("Played", systemImage: "checkmark.circle").foregroundStyle(.secondary)
        }
    }
}

/** An episode's ISO publish date, e.g. "Oct 27, 2026". */
private func published(_ iso: String) -> String {
    let parsed = ISO8601DateFormatter().date(from: iso)
        ?? ISO8601DateFormatter().date(from: String(iso.prefix(10)) + "T00:00:00Z")
    return parsed?.formatted(date: .abbreviated, time: .omitted) ?? iso
}

/**
 * An episode: its art, title, podcast (a link back), date and progress; Play / Resume and
 * Start Over, Mark as Played; the show notes (links open in the browser); then the older and
 * newer episode. As the phone's episode page, without downloads.
 */
struct PodcastEpisodeView: View {
    let seriesId: String
    let episodeId: String
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var player: AudioPlayer
    @EnvironmentObject private var cache: MediaCache
    /// Bumped to reload after marking it, and when playback pauses (for current progress).
    @State private var version = 0

    var body: some View {
        Loaded(id: "\(episodeId)/\(version)", load: { try await model.calls.podcastDetails(seriesId: seriesId) }) { details in
            if let index = details.episodes.firstIndex(where: { $0.id == episodeId }) {
                page(details, index)
            } else {
                ContentUnavailableView("Episode not found", systemImage: "antenna.radiowaves.left.and.right")
            }
        }
        .onChange(of: player.isPlaying) { _, playing in if !playing { version += 1 } }
    }

    private func page(_ details: PodcastDetails, _ index: Int) -> some View {
        let series = details.series
        let episode = details.episodes[index]
        let started = episode.progress as? EpisodeProgressStarted
        let played = episode.progress is EpisodeProgressComplete
        // Listed newest first: the previous episode is the older one.
        let older = details.episodes.indices.contains(index + 1) ? details.episodes[index + 1] : nil
        let newer = index > 0 ? details.episodes[index - 1] : nil
        return ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .top, spacing: 20) {
                    // Episode art is often wide (a video still): shown whole.
                    Artwork(url: episode.imageUri.isEmpty ? series.imageUri : episode.imageUri,
                            symbol: "antenna.radiowaves.left.and.right", fit: true)
                        .frame(width: 260)
                        .frame(maxHeight: 260)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(episode.title).font(.title.bold()).lineLimit(4)
                            .fixedSize(horizontal: false, vertical: true)
                        NavigationLink(value: Route.podcast(series.id)) {
                            Text(series.title).font(.title2).foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        HStack {
                            Text(published(episode.date)).foregroundStyle(.secondary)
                            EpisodeStatus(progress: episode.progress)
                        }
                        HStack {
                            Button { play(details, index, fromStart: false) } label: {
                                Label(started != nil ? "Resume" : "Play", systemImage: "play.fill")
                            }
                            .buttonStyle(.borderedProminent)
                            if started != nil {
                                Button { play(details, index, fromStart: true) } label: {
                                    Label("Start Over", systemImage: "arrow.counterclockwise")
                                }
                            }
                            Button { mark(episode, played: !played) } label: {
                                Label(played ? "Mark as Not Played" : "Mark as Played",
                                      systemImage: played ? "circle" : "checkmark.circle")
                            }
                            DownloadButton(etags: [episode.eid]) {
                                Task {
                                    guard let queue = try? await model.calls.episodesQueue(
                                        title: series.title, imageUri: series.imageUri,
                                        episodes: details.episodes, startIndex: Int32(index)
                                    ) else { return }
                                    cache.download(queue, only: [queue.items[index]])
                                }
                            }
                        }
                        .controlSize(.large)
                        .padding(.top, 4)
                    }
                }
                if !episode.description_.isEmpty {
                    Text(ShowNotes.attributed(episode.description_))
                        .textSelection(.enabled)
                        .frame(maxWidth: 760, alignment: .leading)
                }
                HStack {
                    if let older {
                        NavigationLink(value: Route.podcastEpisode(seriesId: series.id, episodeId: older.id)) {
                            Label("Previous Episode", systemImage: "chevron.left")
                        }
                    }
                    if let newer {
                        NavigationLink(value: Route.podcastEpisode(seriesId: series.id, episodeId: newer.id)) {
                            Label("Next Episode", systemImage: "chevron.right")
                        }
                    }
                }
            }
            .padding(24)
        }
        .navigationTitle(series.title)
    }

    private func play(_ details: PodcastDetails, _ index: Int, fromStart: Bool) {
        model.play(from: fromStart ? index : nil) {
            try await model.calls.episodesQueue(
                title: details.series.title, imageUri: details.series.imageUri,
                episodes: details.episodes, startIndex: Int32(index)
            )
        }
    }

    private func mark(_ episode: PodcastEpisode, played: Bool) {
        Task {
            do {
                try await model.calls.markPlayed(eid: episode.eid, played: played)
                version += 1
            } catch {
                NSLog("Mark played failed: \(error)")
            }
        }
    }
}

/** A feed's show notes: HTML (or plain text with line breaks) as styled text, or plain text. */
enum ShowNotes {
    @MainActor private static var cache: [String: AttributedString] = [:]

    /** With paragraphs and links (which open in the browser), in the system font. */
    @MainActor
    static func attributed(_ notes: String) -> AttributedString {
        if let cached = cache[notes] { return cached }
        let html = notes.contains("<") ? notes : notes
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: "\n", with: "<br>")
        // Sized like body text, in the system font and colour.
        let styled = "<div style=\"font-family: -apple-system; font-size: 13px\">\(html)</div>"
        var result = AttributedString(notes)
        if let data = styled.data(using: .utf8),
           let parsed = try? NSAttributedString(
               data: data,
               options: [.documentType: NSAttributedString.DocumentType.html, .characterEncoding: String.Encoding.utf8.rawValue],
               documentAttributes: nil
           ),
           var converted = try? AttributedString(parsed, including: \.appKit) {
            // The parser sets black text (and its font); drop them to follow light / dark mode.
            converted.appKit.foregroundColor = nil
            converted.foregroundColor = nil
            converted.font = nil
            result = converted
        }
        cache[notes] = result
        return result
    }

    /** Tags dropped and entities decoded, for one- or two-line previews. */
    static func plain(_ notes: String) -> String {
        guard notes.contains("<") || notes.contains("&") else { return notes }
        var text = notes.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
        for (entity, char) in ["&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&#39;": "'", "&nbsp;": " "] {
            text = text.replacingOccurrences(of: entity, with: char)
        }
        return text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }
}
