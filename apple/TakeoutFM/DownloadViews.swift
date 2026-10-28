import SwiftUI
import Shared

/**
 * Download for an album, playlist, episode or title: "Download", then "3 of 12" while it
 * downloads, then "Downloaded" (whose menu removes it). [etags] are what it covers.
 */
struct DownloadButton: View {
    let etags: [String]
    let start: () -> Void
    @EnvironmentObject private var cache: MediaCache

    var body: some View {
        let p = cache.progress(etags)
        if p.total > 0 && p.done == p.total {
            Menu {
                Button("Remove Download", role: .destructive) { cache.remove(etags) }
            } label: {
                Label("Downloaded", systemImage: "checkmark.circle")
            }
            .fixedSize()
        } else if p.active > 0 {
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text(p.total > 1 ? "\(p.done) of \(p.total)" : "Downloading").foregroundStyle(.secondary)
            }
        } else {
            Button(action: start) { Label("Download", systemImage: "arrow.down.circle") }
                .disabled(p.total == 0)
        }
    }
}

/** A track's or episode's download state in a list: ✓ when it's here, progress while it downloads. */
struct DownloadBadge: View {
    let etag: String
    @EnvironmentObject private var cache: MediaCache

    var body: some View {
        switch cache.status[etag] {
        case .done:
            Image(systemName: "arrow.down.circle.fill").foregroundStyle(.secondary).help("Downloaded")
        case .downloading(let fraction):
            ProgressView(value: fraction).progressViewStyle(.circular).controlSize(.mini)
        case .queued:
            ProgressView().controlSize(.mini)
        case .failed:
            Image(systemName: "exclamationmark.circle").foregroundStyle(.red).help("Download failed")
        case nil:
            EmptyView()
        }
    }
}

/** Everything downloaded, by the album, playlist, podcast or title it came with. */
struct DownloadsView: View {
    @EnvironmentObject private var cache: MediaCache
    @EnvironmentObject private var player: AudioPlayer
    @Environment(\.playVideo) private var playVideo
    @State private var confirmRemoveAll = false

    private struct DownloadGroup: Identifiable {
        let id: String
        let title: String
        let entries: [MediaCache.Entry]
    }

    private var groups: [DownloadGroup] {
        Dictionary(grouping: cache.entries.values, by: \.group)
            .map { DownloadGroup(id: $0.key, title: $0.value.first?.groupTitle ?? "", entries: $0.value.sorted { $0.title < $1.title }) }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }

    var body: some View {
        Group {
            if cache.entries.isEmpty {
                ContentUnavailableView("No Downloads", systemImage: "arrow.down.circle",
                                       description: Text("Download albums, playlists, episodes and videos to play them offline."))
            } else {
                List {
                    ForEach(groups) { group in
                        Section {
                            ForEach(group.entries) { entry in row(entry, in: group) }
                        } header: {
                            HStack {
                                Text(group.title).font(.headline)
                                Text("\(group.entries.count) · \(formatBytes(group.entries.reduce(0) { $0 + $1.bytes }))")
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Button("Remove") { cache.remove(group.entries.map(\.etag)) }
                                    .buttonStyle(.borderless)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Downloads")
        .toolbar {
            Text("Uses \(formatBytes(cache.usedBytes))").foregroundStyle(.secondary)
            Button("Remove All") { confirmRemoveAll = true }
                .disabled(cache.entries.isEmpty)
        }
        .confirmationDialog("Remove all downloads?", isPresented: $confirmRemoveAll) {
            Button("Remove All (frees \(formatBytes(cache.usedBytes)))", role: .destructive) { cache.removeAll() }
        }
    }

    private func row(_ entry: MediaCache.Entry, in group: DownloadGroup) -> some View {
        HStack(spacing: 12) {
            Artwork(url: entry.imageUri, symbol: entry.video ? "film" : "music.note")
                .frame(width: entry.video ? 64 : 36, height: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title).lineLimit(1)
                let detail = [entry.artist, entry.album].filter { !$0.isEmpty && $0 != entry.title }.joined(separator: " · ")
                if !detail.isEmpty {
                    Text(detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if entry.isDone {
                Text(formatBytes(entry.bytes)).foregroundStyle(.secondary)
            }
            DownloadBadge(etag: entry.etag)
        }
        .contentShape(Rectangle())
        .onTapGesture { play(entry, in: group) }
        .contextMenu {
            Button("Play") { play(entry, in: group) }
            if cache.status[entry.etag] == .failed {
                Button("Try Again") { cache.retry(entry.etag) }
            }
            Button("Remove Download", role: .destructive) { cache.remove([entry.etag]) }
        }
    }

    /// Music and episodes play as their group's downloaded items; videos in the video window.
    private func play(_ entry: MediaCache.Entry, in group: DownloadGroup) {
        if entry.video {
            playVideo(VideoPlayRequest(id: entry.group))
            return
        }
        let playable = group.entries.filter { !$0.video && $0.isDone }
        guard let start = playable.firstIndex(of: entry) else { return }
        let items = playable.map {
            QueueItem(title: $0.title, artist: $0.artist, album: $0.album, imageUri: $0.imageUri,
                      backgroundUri: "", uri: $0.location, etag: $0.etag, podcast: $0.podcast)
        }
        player.play(MusicQueue(title: group.title, creator: "", imageUri: items.first?.imageUri ?? "",
                               items: items, startIndex: 0, startPositionMs: 0, location: ""),
                    from: start)
    }
}
