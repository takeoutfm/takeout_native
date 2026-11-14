import Combine
import Foundation
import Shared

/**
 * Downloads on this Mac: whole media files in Application Support/TakeoutFM/Media, keyed by
 * ETag (stream URLs redirect to presigned, expiring ones, so a URL is no key), with an index
 * (downloads.json) of what each one is and the album, playlist, podcast or title it was
 * downloaded with. As on Android: playback reads them first (so they play offline), live
 * streams (no ETag) are never cached, and what Playback Caching kept ([Entry.cached]) stays
 * within Settings → Cache size ([purge], the shared CachePolicy) while the viewer's own
 * downloads stay until removed.
 *
 * A download gets its presigned URL from Takeout with the media token, then fetches it
 * without (storage refuses a second kind of authorization); two at a time.
 */
@MainActor
final class MediaCache: NSObject, ObservableObject {
    /** One downloaded (or downloading) file. */
    struct Entry: Codable, Identifiable, Hashable {
        let etag: String
        let title: String
        var artist = ""
        var album = ""
        var imageUri = ""
        var podcast = false
        var video = false
        /** The Takeout location it came from. */
        let location: String
        /** The album, playlist, podcast or title it was downloaded with, and its name. */
        let group: String
        let groupTitle: String
        var fileName: String?
        var bytes: Int64 = 0
        /// Kept by Playback Caching (the purge may remove it). Optional, as the next one, so
        /// indexes from before decode: missing, it's the viewer's.
        var cached: Bool?
        /// When it was last played, or downloaded if never since (epoch ms).
        var lastUsedMs: Int64?
        var id: String { etag }
        var isDone: Bool { fileName != nil }
        var isCached: Bool { cached == true }
    }

    /// Playback Caching's share and the disk's space, after the latest purge.
    struct Usage: Equatable {
        var cachedBytes: Int64 = 0
        var downloadedBytes: Int64 = 0
        var budgetBytes: Int64 = 0
        /// The viewer's downloads (or other apps) leave too little free: warn.
        var lowSpace = false
        /// Downloaded movies and episodes watched to their end: offered for removal.
        var watched: [String] = []
    }

    /// What `downloadVideo` found: room, or not enough even with the cache emptied.
    struct Room {
        let enough: Bool
        let fits: Bool
        let needBytes: Int64
        let freeableBytes: Int64
    }

    enum Status: Equatable {
        case queued
        case downloading(Double)
        case done
        case failed
    }

    @Published private(set) var entries: [String: Entry] = [:]
    @Published private(set) var status: [String: Status] = [:]
    @Published private(set) var usage = Usage()

    /// The ETag the audio player has on (set by the app): the purge leaves it.
    var playing: @MainActor () -> String? = { nil }

    private let calls: SwiftCalls
    private let directory: URL
    private let indexURL: URL
    private var pending: [String] = []
    private var active: [Int: String] = [:]  // task id → etag
    private var session: URLSession!
    private static let parallel = 2

    init(calls: SwiftCalls, directory: URL) {
        self.calls = calls
        self.directory = directory.appendingPathComponent("Media", isDirectory: true)
        indexURL = self.directory.appendingPathComponent("downloads.json")
        super.init()
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
        session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
        load()
    }

    // MARK: Asking

    /** The downloaded file for [etag], if it's wholly here. */
    func localURL(_ etag: String) -> URL? {
        guard !etag.isEmpty, let name = entries[etag]?.fileName else { return nil }
        let url = directory.appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func isDownloaded(_ etag: String) -> Bool { status[etag] == .done }

    /** How much of [etags] is here: (downloaded, in progress, total). */
    func progress(_ etags: [String]) -> (done: Int, active: Int, total: Int) {
        let known = etags.filter { !$0.isEmpty }
        let done = known.filter { status[$0] == .done }.count
        let active = known.filter { if case .downloading = status[$0] { return true }; return status[$0] == .queued }.count
        return (done, active, known.count)
    }

    var usedBytes: Int64 { entries.values.filter(\.isDone).reduce(0) { $0 + $1.bytes } }

    // MARK: Downloading

    /** The group a queue's downloads go in: its server location, else its title (a podcast). */
    static func group(of queue: MusicQueue) -> String {
        queue.location.isEmpty ? "title:\(queue.title)" : queue.location
    }

    /** Downloads [queue]'s items (all, or [only] those), grouped with the queue; [cached]: Playback Caching's. */
    func download(_ queue: MusicQueue, only: [QueueItem]? = nil, cached: Bool = false) {
        download(only ?? queue.items, group: Self.group(of: queue), groupTitle: queue.title, cached: cached)
    }

    /**
     * Downloads [items] (a queue's tracks or episodes) as part of [group]: Playback Caching's
     * when [cached] (the purge may remove them), else the viewer's, which also makes any of
     * them already cached the viewer's.
     */
    func download(_ items: [QueueItem], group: String, groupTitle: String, cached: Bool = false) {
        for item in items where !item.etag.isEmpty {
            add(Entry(etag: item.etag, title: item.title, artist: item.artist, album: item.album,
                      imageUri: item.imageUri, podcast: item.podcast, location: item.uri,
                      group: group, groupTitle: groupTitle, cached: cached ? true : nil))
        }
        save()
        startNext()
    }

    /**
     * Downloads a movie or episode. A file of known size first gets room (cached tracks go);
     * if even that isn't enough it isn't started (unless [anyway]) and the answer says so.
     */
    @discardableResult
    func downloadVideo(_ details: VideoDetails, anyway: Bool = false) -> Room {
        let started = Room(enough: true, fits: true, needBytes: details.size, freeableBytes: 0)
        guard !details.etag.isEmpty, !details.videoUri.isEmpty else { return started }
        if !anyway, details.size > 0, let space = volume() {
            let room = calls.cacheRoom(entries: cacheEntries(finished: []), totalBytes: space.total, freeBytes: space.free, needBytes: details.size)
            guard room.enough else {
                return Room(enough: false, fits: room.fits, needBytes: details.size, freeableBytes: room.freeableBytes)
            }
            remove(room.remove)
        }
        add(Entry(etag: details.etag, title: details.title, imageUri: details.backdropUri, video: true,
                  location: details.videoUri, group: details.id, groupTitle: details.title))
        save()
        startNext()
        return started
    }

    /// [etag] is playing: the most recently used, for the purge's order.
    func touch(_ etag: String) {
        guard entries[etag]?.isDone == true else { return }
        entries[etag]?.lastUsedMs = Self.nowMs()
        save()
    }

    /** Downloads a failed entry again, as it was. */
    func retry(_ etag: String) {
        guard entries[etag] != nil, status[etag] == .failed else { return }
        status[etag] = .queued
        pending.append(etag)
        startNext()
    }

    private func add(_ entry: Entry) {
        // Only cached until now, downloaded by the viewer now: theirs, never purged.
        if !entry.isCached, entries[entry.etag]?.isCached == true { entries[entry.etag]?.cached = nil }
        if let existing = status[entry.etag], existing != .failed { return }
        entries[entry.etag] = entry
        status[entry.etag] = .queued
        pending.append(entry.etag)
    }

    private func startNext() {
        while active.count < Self.parallel, !pending.isEmpty {
            let etag = pending.removeFirst()
            guard let entry = entries[etag] else { continue }
            status[etag] = .downloading(0)
            Task {
                do {
                    let url = try await StreamResolver.resolve(entry.location, authorization: calls.mediaAuthorization(url: entry.location))
                    guard entries[etag] != nil else { return }  // removed meanwhile
                    let task = session.downloadTask(with: url)
                    task.taskDescription = etag
                    active[task.taskIdentifier] = etag
                    task.resume()
                } catch {
                    NSLog("Download \(entry.title) failed: \(error)")
                    status[etag] = .failed
                    startNext()
                }
            }
        }
    }

    // MARK: Removing

    func remove(_ etags: [String]) {
        for etag in etags {
            if let task = active.first(where: { $0.value == etag })?.key {
                session.getAllTasks { tasks in tasks.first { $0.taskIdentifier == task }?.cancel() }
            }
            pending.removeAll { $0 == etag }
            if let name = entries[etag]?.fileName {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
            }
            entries[etag] = nil
            status[etag] = nil
        }
        save()
    }

    func removeAll() { remove(Array(entries.keys)) }

    // MARK: Purging

    /**
     * Keeps Playback Caching within its size (the shared rule: finished podcast episodes, then
     * the least recently played; never the viewer's downloads) and counts what's where. Run
     * at start, when a download finishes and when the size changes; [clear] empties the cache.
     */
    func purge(clear: Bool = false) {
        Task {
            let done = entries.values.filter(\.isDone)
            let finished = (try? await calls.finishedEtags(
                episodes: done.filter(\.podcast).map(\.etag),
                videos: done.filter { $0.video && !$0.isCached }.map(\.etag))) ?? []
            guard let space = volume(), space.total > 0 else { return }
            let (total, free) = (space.total, space.free)
            let all = cacheEntries(finished: Set(finished))
            let remove = clear
                ? all.filter(\.cached).map(\.etag)
                : calls.cachePlan(entries: all, totalBytes: total, freeBytes: free).remove
            if !remove.isEmpty { NSLog("Cache purge removes \(remove.count) of \(all.count)") }
            self.remove(remove)
            let (total2, free2) = volume().map { ($0.total, $0.free) } ?? (total, free)
            let after = cacheEntries(finished: Set(finished))
            let plan = calls.cachePlan(entries: after, totalBytes: total2, freeBytes: free2)
            usage = Usage(
                cachedBytes: after.filter(\.cached).reduce(0) { $0 + $1.bytes },
                downloadedBytes: plan.downloadedBytes,
                budgetBytes: plan.budgetBytes,
                lowSpace: plan.lowSpace,
                watched: finished.filter { etag in entries[etag]?.video == true }
            )
        }
    }

    /// What a size choice (0 Auto) comes to on this disk.
    func limitBytes(_ choice: Int64) -> Int64 {
        calls.cacheLimitFor(limitBytes: choice, totalBytes: volume()?.total ?? 0)
    }

    /// Every finished download as the shared rule sees it (the one playing as the viewer's).
    private func cacheEntries(finished: Set<String>) -> [CacheEntry] {
        let current = playing()
        return entries.values.filter(\.isDone).map { e in
            CacheEntry(
                etag: e.etag, bytes: e.bytes, lastUsedMs: e.lastUsedMs ?? 0,
                cached: e.isCached && e.etag != current, partial: false,
                finishedPodcast: e.podcast && finished.contains(e.etag)
            )
        }
    }

    /// The downloads volume's size and free space, in bytes.
    private func volume() -> (total: Int64, free: Int64)? {
        guard let values = try? directory.resourceValues(forKeys: [.volumeTotalCapacityKey, .volumeAvailableCapacityForImportantUsageKey]),
              let total = values.volumeTotalCapacity, let free = values.volumeAvailableCapacityForImportantUsage
        else { return nil }
        return (total: Int64(total), free: free)
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: Index

    private func load() {
        guard let data = try? Data(contentsOf: indexURL),
              let saved = try? JSONDecoder().decode([Entry].self, from: data) else { return }
        for entry in saved {
            entries[entry.etag] = entry
            if entry.isDone, localURL(entry.etag) != nil {
                status[entry.etag] = .done
            } else {
                // Interrupted last time: start again.
                status[entry.etag] = .queued
                pending.append(entry.etag)
            }
        }
        startNext()
        purge()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(Array(entries.values)) else { return }
        try? data.write(to: indexURL, options: .atomic)
    }

    fileprivate func finished(_ etag: String, file: URL, response: URLResponse?) {
        guard var entry = entries[etag] else { return }
        let http = response as? HTTPURLResponse
        guard let http, (200..<300).contains(http.statusCode) else {
            NSLog("Download \(entry.title) failed: HTTP \(http?.statusCode ?? 0)")
            status[etag] = .failed
            return
        }
        // Keep the file's own extension (flac, mp3, mkv…), which players go by.
        let ext = http.url?.pathExtension.isEmpty == false ? http.url!.pathExtension : "media"
        let name = "\(etag.filter { $0.isLetter || $0.isNumber || $0 == "-" }).\(ext)"
        let target = directory.appendingPathComponent(name)
        do {
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: file, to: target)
            entry.fileName = name
            entry.bytes = (try? target.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            entry.lastUsedMs = Self.nowMs()
            entries[etag] = entry
            status[etag] = .done
            save()
            // a track kept: still within the cache size?
            if entry.isCached { purge() }
            #if DEBUG
            NSLog("Downloaded \(entry.title): \(formatBytes(entry.bytes)) as \(name)")
            #endif
        } catch {
            NSLog("Download \(entry.title) couldn't be kept: \(error)")
            status[etag] = .failed
        }
    }

    fileprivate func failed(_ etag: String, error: Error?) {
        guard entries[etag] != nil, status[etag] != .done else { return }
        if (error as? URLError)?.code != .cancelled {
            NSLog("Download failed: \(error.map { "\($0)" } ?? "?")")
        }
        status[etag] = .failed
    }

    fileprivate func progressed(_ etag: String, fraction: Double) {
        if status[etag] != .done { status[etag] = .downloading(fraction) }
    }

    fileprivate func ended(_ taskId: Int) {
        active[taskId] = nil
        startNext()
    }
}

extension MediaCache: URLSessionDownloadDelegate {
    // The session's delegate queue is the main queue.
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The file is deleted when this returns: move it somewhere safe first.
        let keep = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.moveItem(at: location, to: keep)
        let etag = downloadTask.taskDescription ?? ""
        let response = downloadTask.response
        MainActor.assumeIsolated { finished(etag, file: keep, response: response) }
    }

    nonisolated func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0 else { return }
        let etag = downloadTask.taskDescription ?? ""
        let fraction = Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)
        MainActor.assumeIsolated { progressed(etag, fraction: fraction) }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let etag = task.taskDescription ?? ""
        let id = task.taskIdentifier
        MainActor.assumeIsolated {
            if error != nil { failed(etag, error: error) }
            ended(id)
        }
    }
}

/** Bytes as "12 MB" / "1.4 GB". */
func formatBytes(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}
