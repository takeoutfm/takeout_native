import SwiftUI
import Shared

// Movies and TV: ids "m-" (movie), "s-" (series) and "e-" (episode), all opened as Route.video.
// Kotlin's `description` is `description_` here (it clashes with NSObject's).

/** Video home: Continue Watching, then recently added and new movies, and recent TV. */
struct WatchView: View {
    @EnvironmentObject private var model: AppModel

    private struct Rows {
        let continueWatching, recentlyAdded, newReleases, recentShows: [VideoCard]
    }

    var body: some View {
        Loaded(load: {
            async let watching = (try? await model.calls.continueWatching()) ?? []
            async let added = (try? await model.calls.videoRecentlyAdded()) ?? []
            async let new = (try? await model.calls.videoNewReleases()) ?? []
            async let shows = (try? await model.calls.recentShows()) ?? []
            return Rows(continueWatching: await watching, recentlyAdded: await added, newReleases: await new, recentShows: await shows)
        }) { rows in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 28) {
                    VideoRow(title: "Continue Watching", cards: rows.continueWatching, wide: true)
                    VideoRow(title: "Recently Added", cards: rows.recentlyAdded)
                    VideoRow(title: "New Releases", cards: rows.newReleases)
                    VideoRow(title: "TV", cards: rows.recentShows)
                }
                .padding(24)
            }
        }
        .navigationTitle("Watch")
    }
}

/** A poster grid, e.g. every movie or every series. */
struct PosterGrid: View {
    let title: String
    let load: @MainActor () async throws -> [VideoCard]

    var body: some View {
        Loaded(load: load) { cards in
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 18)], spacing: 22) {
                    ForEach(cards, id: \.id) { card in
                        NavigationLink(value: Route.video(card.id)) { PosterCard(card: card) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(20)
            }
        }
        .navigationTitle(title)
    }
}

/** A portrait poster with its title; [progress] shows as a bar. */
struct PosterCard: View {
    let card: VideoCard

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Artwork(url: card.posterUri, symbol: "film")
                .aspectRatio(2 / 3, contentMode: .fit)
                .overlay(alignment: .bottom) { ProgressBar(progress: card.progress?.floatValue) }
            Text(card.title).font(.headline).lineLimit(1)
        }
    }
}

/** A landscape card (episodes, Continue Watching), with progress. */
struct WideCard: View {
    let card: VideoCard

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Artwork(url: card.backdropUri.isEmpty ? card.posterUri : card.backdropUri, symbol: "film")
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay(alignment: .bottom) { ProgressBar(progress: card.progress?.floatValue) }
            Text(card.title).font(.headline).lineLimit(1)
        }
    }
}

private struct ProgressBar: View {
    let progress: Float?

    var body: some View {
        if let progress {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle().fill(.black.opacity(0.5))
                    Rectangle().fill(.tint).frame(width: geo.size.width * CGFloat(min(max(progress, 0), 1)))
                }
            }
            .frame(height: 4)
            .padding(6)
        }
    }
}

/** A titled, horizontally scrolling row of titles; hidden when there are none. */
struct VideoRow: View {
    let title: String
    let cards: [VideoCard]
    var wide = false

    var body: some View {
        if !cards.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(title)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 16) {
                        ForEach(cards, id: \.id) { card in
                            NavigationLink(value: Route.video(card.id)) {
                                if wide {
                                    WideCard(card: card).frame(width: 260)
                                } else {
                                    PosterCard(card: card).frame(width: 140)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
    }
}

/** A movie, series or episode. */
struct VideoView: View {
    let videoId: String
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Loaded(id: videoId, load: { try await model.calls.videoDetails(id: videoId) }) { details in
            VideoPage(details: details)
        }
    }
}

private struct VideoPage: View {
    let details: VideoDetails
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var cache: MediaCache
    @Environment(\.playVideo) private var playVideo
    @State private var resumeMs: Int64 = 0
    @State private var favorite: Bool
    @State private var season: Int32

    init(details: VideoDetails) {
        self.details = details
        _favorite = State(initialValue: details.isFavorite)
        _season = State(initialValue: details.seasons.first?.number ?? 0)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Artwork(url: details.backdropUri, symbol: "film")
                    .aspectRatio(16 / 9, contentMode: .fill)
                    .frame(maxHeight: 380)
                    .clipped()
                VStack(alignment: .leading, spacing: 10) {
                    if let episode = details.episode {
                        NavigationLink(value: Route.video(episode.seriesId)) {
                            Text(episode.seriesTitle).font(.title2).foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        Text("\(episode.label) · \(episode.name)").font(.largeTitle.bold())
                    } else {
                        Text(details.title).font(.largeTitle.bold())
                    }
                    Text(meta).foregroundStyle(.secondary)
                    if !details.tagline.isEmpty && details.kind != .episode {
                        Text(details.tagline).italic()
                    }
                    Text(details.description_).frame(maxWidth: 760, alignment: .leading)
                    buttons.padding(.top, 6)
                }
                if !details.seasons.isEmpty { seasons }
                if let episode = details.episode, episode.seasonEpisodes.count > 1 {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionTitle("Season \(episode.season)")
                        ForEach(episode.seasonEpisodes, id: \.id) { EpisodeRow(episode: $0, current: $0.id == details.id) }
                    }
                }
                if !details.cast.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionTitle("Cast & Crew")
                        ScrollView(.horizontal) {
                            LazyHStack(alignment: .top, spacing: 16) {
                                ForEach(Array(details.cast.enumerated()), id: \.offset) { _, credit in
                                    NavigationLink(value: Route.person(credit.personId)) { CreditCard(credit: credit) }
                                        .buttonStyle(.plain)
                                }
                            }
                            .padding(.bottom, 8)
                        }
                    }
                }
                VideoRow(title: "More Like This", cards: details.similar)
            }
            .padding(24)
        }
        .navigationTitle(details.title)
        .task(id: details.etag) {
            guard details.kind != .series, !details.etag.isEmpty else { return }
            resumeMs = (try? await model.calls.resumePositionMs(etag: details.etag))?.int64Value ?? 0
        }
    }

    private var meta: String {
        var parts: [String] = []
        if !details.year.isEmpty { parts.append(details.year) }
        if !details.rating.isEmpty { parts.append(details.rating) }
        if details.runtimeMinutes > 0 {
            let h = details.runtimeMinutes / 60, m = details.runtimeMinutes % 60
            parts.append(h > 0 ? "\(h)h \(m)m" : "\(m)m")
        }
        if details.seasonCount > 0 { parts.append(details.seasonCount == 1 ? "1 season" : "\(details.seasonCount) seasons") }
        if !details.genres.isEmpty { parts.append(details.genres.joined(separator: ", ")) }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var buttons: some View {
        HStack {
            if !details.videoUri.isEmpty {
                if details.kind == .series {
                    Button { play(startOver: false) } label: {
                        Label(details.playTarget.map { "Play \($0)" } ?? "Play", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                } else if resumeMs > 0 {
                    Button { play(startOver: false) } label: {
                        Label("Resume from \(format(resumeMs))", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    Button { play(startOver: true) } label: { Label("Start Over", systemImage: "arrow.counterclockwise") }
                } else {
                    Button { play(startOver: false) } label: { Label("Play", systemImage: "play.fill") }
                        .buttonStyle(.borderedProminent)
                }
            }
            if details.kind != .series && !details.etag.isEmpty && !details.videoUri.isEmpty {
                DownloadButton(etags: [details.etag]) { cache.downloadVideo(details) }
            }
            if let path = details.favoritePath {
                Button { toggleFavorite(path) } label: { Image(systemName: favorite ? "heart.fill" : "heart") }
                    .help(favorite ? "Remove from Favorites" : "Add to Favorites")
            }
            if let previous = details.previousEpisodeId {
                NavigationLink(value: Route.video(previous)) { Label("Previous", systemImage: "chevron.left") }
            }
            if let next = details.nextEpisodeId {
                NavigationLink(value: Route.video(next)) { Label("Next", systemImage: "chevron.right") }
            }
        }
        .controlSize(.large)
    }

    private var seasons: some View {
        VStack(alignment: .leading, spacing: 8) {
            if details.seasons.count > 1 {
                Picker("Season", selection: $season) {
                    ForEach(details.seasons, id: \.number) { s in
                        Text(s.number == 0 ? "Specials" : "Season \(s.number)").tag(s.number)
                    }
                }
                .fixedSize()
            } else {
                SectionTitle(season == 0 ? "Specials" : "Season \(season)")
            }
            ForEach(details.seasons.first { $0.number == season }?.episodes ?? [], id: \.id) {
                EpisodeRow(episode: $0, current: false)
            }
        }
    }

    private func play(startOver: Bool) {
        // A series plays its next episode, which details resolve from the series id.
        playVideo(VideoPlayRequest(id: details.id, startOver: startOver))
    }

    private func toggleFavorite(_ path: String) {
        let wanted = !favorite
        favorite = wanted
        Task {
            do {
                try await model.calls.setVideoFavorite(path: path, favorite: wanted)
            } catch {
                NSLog("Favorite failed: \(error)")
                favorite = !wanted
            }
        }
    }

    private func format(_ ms: Int64) -> String {
        let s = Int(ms / 1000)
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }
}

/** An episode in a season list; opens its page, or plays it from the ▶ button. */
private struct EpisodeRow: View {
    let episode: EpisodeInfo
    let current: Bool
    @Environment(\.playVideo) private var playVideo

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            NavigationLink(value: Route.video(episode.id)) {
                HStack(alignment: .top, spacing: 14) {
                    Artwork(url: episode.stillUri, symbol: "tv")
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .frame(width: 180)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(episode.episode). \(episode.name)").font(.headline)
                        if episode.runtimeMinutes > 0 {
                            Text("\(episode.runtimeMinutes)m").foregroundStyle(.secondary)
                        }
                        Text(episode.description_).foregroundStyle(.secondary).lineLimit(2)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(current)
            Button { playVideo(VideoPlayRequest(id: episode.id)) } label: {
                Image(systemName: "play.circle").font(.title)
            }
            .buttonStyle(.borderless)
            .help("Play")
        }
        .padding(.vertical, 4)
        .background(current ? Color.accentColor.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct CreditCard: View {
    let credit: CastCredit

    var body: some View {
        VStack(spacing: 4) {
            Artwork(url: credit.avatarUri, symbol: "person.fill")
                .aspectRatio(1, contentMode: .fit)
                .clipShape(Circle())
            Text(credit.name).font(.headline).lineLimit(1)
            Text(credit.role).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(width: 110)
    }
}

/** A person: photo, life summary, biography and their titles by kind of credit. */
struct PersonView: View {
    let personId: String
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Loaded(id: personId, load: { try await model.calls.person(personId: personId) }) { person in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    HeaderStack(alignment: .top) {
                        Artwork(url: person.photoUri, symbol: "person.fill")
                            .frame(width: 160, height: 240)
                        VStack(alignment: .leading, spacing: 8) {
                            Text(person.name).font(.largeTitle.bold())
                            if !person.lifeSummary.isEmpty {
                                Text(person.lifeSummary).foregroundStyle(.secondary)
                            }
                            Text(person.bio).lineLimit(10).frame(maxWidth: 700, alignment: .leading)
                        }
                    }
                    ForEach(Array(person.credits.enumerated()), id: \.offset) { _, group in
                        VideoRow(title: title(group.kind), cards: group.titles)
                    }
                }
                .padding(24)
            }
            .navigationTitle(person.name)
        }
    }

    // Kotlin/Native lowercases these multi-word enum entries.
    private func title(_ kind: CreditKind) -> String {
        switch kind {
        case .moviesacting: return "Movies"
        case .moviesdirecting: return "Directed"
        case .movieswriting: return "Written"
        case .showsacting: return "TV"
        case .showsdirecting: return "TV Directed"
        case .showswriting: return "TV Written"
        default: return ""
        }
    }
}
