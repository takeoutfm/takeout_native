import Combine
import Shared
import SwiftUI
#if os(macOS)
import VLCKit
#else
import MobileVLCKit
#endif

/** What the video window plays: a movie or episode id, from its saved place or the start. */
struct VideoPlayRequest: Codable, Hashable {
    let id: String
    var startOver = false
}

/** One embedded audio or subtitle track VLC reports (index -1: subtitles off). */
struct MediaTrack: Identifiable, Hashable {
    let index: Int32
    let name: String
    /** A second line (an audio track's language), or "". */
    var detail = ""
    var id: Int32 { index }
}

/**
 * Plays one movie or episode with VLCKit, resuming where it was left and saving progress (every
 * 10 seconds while playing, on pause and on close) through the shared TakeoutProgress. An
 * episode that ends goes on to the next one.
 */
@MainActor
final class VideoPlayerModel: NSObject, ObservableObject, VLCMediaPlayerDelegate {
    @Published private(set) var details: VideoDetails?
    /// A downloaded title watched to its end: the player asks whether to remove its download.
    @Published var watchedDownload: MediaCache.Entry? {
        // the question answered: the player leaves (the title has ended)
        didSet { if oldValue != nil && watchedDownload == nil { finished = true } }
    }
    /**
     * A movie or a series' last episode played to its end: the player leaves, back to where it
     * was played from (as on the phone and TV), rather than staying on a stopped picture.
     */
    @Published private(set) var finished = false
    @Published private(set) var failure: String?
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = true
    @Published private(set) var timeMs: Int64 = 0
    @Published private(set) var lengthMs: Int64 = 0
    #if DEBUG
    private var seekedNearEnd = false
    #endif
    @Published private(set) var audioTracks: [MediaTrack] = []
    @Published private(set) var subtitleTracks: [MediaTrack] = []
    @Published private(set) var audioTrack: Int32 = -1
    @Published private(set) var subtitleTrack: Int32 = -1
    /** The playback speed (VIDEO-8). */
    @Published private(set) var speed: Float = 1

    #if os(macOS)
    let videoView = VLCVideoView()
    #else
    let videoView = UIView()
    #endif
    private let calls: SwiftCalls
    private let cache: MediaCache
    private let player = VLCMediaPlayer()
    /** This title's subtitles were set as picked last (once VLC listed them). */
    private var subtitlesChosen = false
    private var hasPlayed = false
    /// This title's end was handled (`ended`), so a second end event changes nothing.
    private var endHandled = false
    private var savedAt = Date.distantPast
    private var loading: Task<Void, Never>?
    /// This video's id with the stream proxy, while it's registered.
    private var proxied: String?

    init(calls: SwiftCalls, cache: MediaCache) {
        self.calls = calls
        self.cache = cache
        super.init()
        #if os(macOS)
        videoView.backColor = .black
        #else
        videoView.backgroundColor = .black
        #endif
        player.drawable = videoView
        player.delegate = self
    }

    func load(_ request: VideoPlayRequest) {
        save()
        player.stop()
        unregister()
        loading?.cancel()
        details = nil
        failure = nil
        finished = false
        hasPlayed = false
        endHandled = false
        isBuffering = true
        timeMs = 0
        lengthMs = 0
        audioTracks = []
        subtitleTracks = []
        subtitlesChosen = false
        loading = Task {
            do {
                let d = try await calls.videoDetails(id: request.id)
                details = d
                guard !d.videoUri.isEmpty else {
                    failure = "There's no video file for this title."
                    return
                }
                let resumeMs = request.startOver ? 0 : ((try? await calls.resumePositionMs(etag: d.etag))?.int64Value ?? 0)
                // Takeout's own streams go through the proxy (VLC can't send the token).
                var url = URL(string: d.videoUri)
                if let file = cache.localURL(d.etag) {
                    url = file  // downloaded: from disk, offline too
                } else if let authorization = calls.mediaAuthorization(url: d.videoUri) {
                    let stream = try await StreamProxy.shared.register(location: d.videoUri, authorization: authorization)
                    proxied = stream.id
                    url = stream.url
                }
                try Task.checkCancellation()
                guard let url else { throw URLError(.badURL) }
                let media = VLCMedia(url: url)
                if resumeMs > 0 { media.addOption(":start-time=\(Double(resumeMs) / 1000)") }
                player.media = media
                player.play()
                if speed != 1 { player.rate = speed }
            } catch is CancellationError {
            } catch {
                NSLog("Video \(request.id) failed: \(error)")
                failure = "Couldn't play this."
            }
        }
    }

    func togglePlay() {
        if player.isPlaying { player.pause() } else { player.play() }
    }

    func jump(seconds: Int32) {
        if seconds < 0 { player.jumpBackward(-seconds) } else { player.jumpForward(seconds) }
    }

    func seek(toMs ms: Int64) {
        player.time = VLCTime(int: Int32(clamping: ms))
        timeMs = ms
    }

    func selectAudio(_ index: Int32) {
        player.currentAudioTrackIndex = index
        audioTrack = index
        // The language picked becomes the preference for the next title, as on the phone.
        if let media = player.media {
            AudioTrackRanking.remember(AudioTrackRanking.tracks(media).first { $0.id == index }?.language)
        }
    }

    func selectSubtitle(_ index: Int32) {
        player.currentVideoSubTitleIndex = index
        subtitleTrack = index
        // The language picked is used for the next titles (the live player's too).
        player.rememberSubtitle(index)
    }

    /** The speeds the player offers (VIDEO-8), as on Linux. */
    static let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 2]

    /** Plays at [rate] (1 is normal); kept for the next episode while the player is open. */
    func setSpeed(_ rate: Float) {
        player.rate = rate
        speed = rate
    }

    /** Saves the place and stops (the window closed). */
    func close() {
        loading?.cancel()
        save()
        player.stop()
        unregister()
    }

    private func unregister() {
        if let proxied { StreamProxy.shared.unregister(proxied) }
        proxied = nil
    }

    /** Saves where we are, once playback really started (a failed load never resets it). */
    private func save() {
        guard hasPlayed, let etag = details?.etag, !etag.isEmpty else { return }
        savedAt = Date()
        #if DEBUG
        // For test runs that mustn't change the user's real progress: `-noProgress YES`.
        if UserDefaults.standard.bool(forKey: "noProgress") { return }
        #endif
        calls.reportProgress(etag: etag, positionMs: timeMs, durationMs: lengthMs)
    }

    private func refreshTracks() {
        defer {
            #if DEBUG
            NSLog("Tracks: audio \(audioTracks.map { "\($0.name) / \($0.detail)" }) (\(audioTrack)), subtitles \(subtitleTracks.map(\.name)) (\(subtitleTrack))")
            #endif
        }
        audioTracks = player.labelledAudioTracks
        subtitleTracks = player.subtitleTrackList
        audioTrack = player.currentAudioTrackIndex
        subtitleTrack = player.currentVideoSubTitleIndex
    }

    // MARK: VLCMediaPlayerDelegate (VLCKit calls these on the main thread)

    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor in self.stateChanged() }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor in self.timeChanged() }
    }

    private func stateChanged() {
        let state = player.state
        #if DEBUG
        if state != .buffering { NSLog("VLC \(VLCMediaPlayerStateToString(state)) at \(player.time.intValue) ms") }
        #endif
        isPlaying = player.isPlaying
        isBuffering = state == .opening || state == .buffering && !hasPlayed
        switch state {
        case .playing:
            if !hasPlayed { started() }
        case .paused:
            save()
            // not on Stopped: loading the next episode stops this one at its end
            if hasPlayed && player.isAtEnd { ended() }
        case .ended:
            ended()
        case .error:
            NSLog("VLC error playing \(details?.id ?? "?")")
            failure = "Couldn't play this."
        default:
            break
        }
    }

    /**
     * The title has played to its end (VLC says Ended, or stops paused at the end, as it did
     * after a seek near it, which left the player on a stopped picture): once per title.
     */
    private func ended() {
        guard !endHandled else { return }
        endHandled = true
        save()
        // An episode ends: on to the next one, from its start.
        if let next = details?.nextEpisodeId {
            load(VideoPlayRequest(id: next, startOver: true))
        } else if let etag = details?.etag, let entry = cache.entries[etag], entry.isDone, !entry.isCached {
            // watched to its end: offered for removal (never removed by itself), then leaves
            watchedDownload = entry
        } else {
            finished = true
        }
    }

    private func started() {
        hasPlayed = true
        savedAt = Date()  // first save 10 s in, as on the phone
        chooseAudio()
        chooseSubtitles()
        refreshTracks()
    }

    /// Subtitles in the language picked last, once VLC lists this title's (they can come late).
    private func chooseSubtitles() {
        if !subtitlesChosen { subtitlesChosen = player.applySubtitlePreference() }
    }

    /// Switches to the best audio track for this title (once; the viewer can change it).
    private func chooseAudio() {
        player.chooseBestAudioTrack()
    }

    private func timeChanged() {
        timeMs = Int64(player.time.intValue)
        lengthMs = Int64(player.media?.length.intValue ?? 0)
        isBuffering = false
        // VLCKit 3 reports .playing once, then keeps saying "buffering" while it plays; a
        // moving clock is what shows playback started.
        if !hasPlayed && player.isPlaying { started() }
        // Tracks can appear after the first frames (e.g. subtitles in a later cluster).
        if hasPlayed && !subtitlesChosen { chooseSubtitles() }
        if audioTracks.isEmpty || subtitleTracks.isEmpty { refreshTracks() }
        #if DEBUG
        // For testing a title's end without watching it all: `-seekNearEnd YES` (with
        // `-noProgress YES`, nothing is saved).
        if UserDefaults.standard.bool(forKey: "seekNearEnd"), !seekedNearEnd, hasPlayed, lengthMs > 20_000, timeMs > 1_000 {
            seekedNearEnd = true
            seek(toMs: lengthMs - 5_000)
        }
        #endif
        if Date().timeIntervalSince(savedAt) > 10 { save() }
    }
}

/** VLC's tracks as the players' menus show them, and the shared best-audio-track choice. */
extension VLCMediaPlayer {
    /**
     * The audio tracks, named from their details by the shared label ("Dolby Digital ·
     * Surround 5.1" over "English"): files often name every track alike.
     */
    var labelledAudioTracks: [MediaTrack] {
        let details = media.map(AudioTrackRanking.tracks) ?? []
        return Self.tracks(names: audioTrackNames, indexes: audioTrackIndexes)
            .filter { $0.index >= 0 }
            .enumerated()
            .map { number, track in
                guard let details = details.first(where: { $0.id == track.index }) else { return track }
                let label = AudioTrackRanking.label(details, number: number + 1)
                return MediaTrack(index: track.index, name: label.title, detail: label.detail)
            }
    }

    /**
     * The subtitle tracks, Off (-1) first, labelled by the shared rule (TRACKS-3): "English ·
     * SDH · PGS" from VLC's track details, not its own "Track 1 - [English]". VLC gives no
     * forced or SDH flags, so the track's name stands in.
     */
    var subtitleTrackList: [MediaTrack] {
        var details: [Int32: [String: Any]] = [:]
        for info in media?.tracksInformation ?? [] {
            guard let info = info as? [String: Any],
                  info[VLCMediaTracksInformationType] as? String == VLCMediaTracksInformationTypeText,
                  let id = (info[VLCMediaTracksInformationId] as? NSNumber)?.int32Value else { continue }
            details[id] = info
        }
        var number = 0
        return Self.tracks(names: videoSubTitlesNames, indexes: videoSubTitlesIndexes).map { track in
            guard track.index >= 0 else { return MediaTrack(index: -1, name: "Off") }
            number += 1
            let info = details[track.index] ?? [:]
            let language = AudioTrackRankingKt.normalizeLanguage(tag: info[VLCMediaTracksInformationLanguage] as? String)
                .map { Locale.current.localizedString(forLanguageCode: $0) ?? $0 }
            var name = info[VLCMediaTracksInformationDescription] as? String ?? ""
            if SubtitleTrackLabelKt.isGeneratedTrackName(name: name) { name = "" }
            let fourcc = (info[VLCMediaTracksInformationCodec] as? NSNumber)?.uint32Value ?? 0
            let codec = VLCMedia.codecName(forFourCC: fourcc, trackType: VLCMediaTracksInformationTypeText)
            let lower = name.lowercased()
            let label = SubtitleTrackLabelKt.subtitleTrackLabel(
                languageName: language,
                name: name,
                forced: lower.contains("forced"),
                sdh: lower.contains("sdh") || lower.contains("hearing"),
                format: SubtitleTrackLabelKt.subtitleFormat(codec: codec),
                number: Int32(number)
            )
            return MediaTrack(index: track.index, name: label)
        }
    }

    /** The languages of the subtitle tracks, by track id (VLC's names don't always say). */
    var subtitleLanguages: [Int32: String] {
        var languages: [Int32: String] = [:]
        for info in media?.tracksInformation ?? [] {
            guard let info = info as? [String: Any],
                  info[VLCMediaTracksInformationType] as? String == VLCMediaTracksInformationTypeText,
                  let id = (info[VLCMediaTracksInformationId] as? NSNumber)?.int32Value,
                  let language = AudioTrackRankingKt.normalizeLanguage(tag: info[VLCMediaTracksInformationLanguage] as? String)
            else { continue }
            languages[id] = language
        }
        return languages
    }

    private static let subtitleKey = "subtitleLanguage"

    /**
     * Remembers subtitle track [index] (-1: off) for the next titles, by language: "" for off,
     * as on the phone, TV and Linux. A track of no language changes nothing.
     */
    func rememberSubtitle(_ index: Int32) {
        let language = index < 0 ? "" : subtitleLanguages[index]
        if let language { UserDefaults.standard.set(language, forKey: Self.subtitleKey) }
    }

    /**
     * Selects the subtitles picked last (TRACKS-6): off, or by the shared rule the first track in
     * that language that isn't forced (a forced one only if that's all it has). Nothing picked
     * yet, or no track in it, leaves VLC's choice, the file's default (its forced ones stay on).
     * False while VLC lists no subtitle tracks yet, so it's tried again.
     */
    func applySubtitlePreference() -> Bool {
        guard let language = UserDefaults.standard.string(forKey: Self.subtitleKey) else { return true }
        if language.isEmpty {
            currentVideoSubTitleIndex = -1
            return true
        }
        let candidates = subtitleCandidates
        if candidates.isEmpty { return false }
        if let id = SubtitleTrackLabelKt.pickSubtitleTrack(tracks: candidates, language: language) {
            currentVideoSubTitleIndex = id.int32Value
        }
        return true
    }

    /** The subtitle tracks in VLC's order, for the shared pick (VLC has no forced flag: the name says). */
    private var subtitleCandidates: [SubtitleCandidate] {
        (media?.tracksInformation ?? []).compactMap { info -> SubtitleCandidate? in
            guard let info = info as? [String: Any],
                  info[VLCMediaTracksInformationType] as? String == VLCMediaTracksInformationTypeText,
                  let id = (info[VLCMediaTracksInformationId] as? NSNumber)?.int32Value else { return nil }
            let language = AudioTrackRankingKt.normalizeLanguage(tag: info[VLCMediaTracksInformationLanguage] as? String)
            let name = info[VLCMediaTracksInformationDescription] as? String ?? ""
            return SubtitleCandidate(id: id, language: language, forced: SubtitleTrackLabelKt.looksForced(name: name))
        }
        .sorted { $0.id < $1.id }
    }

    /** Switches to the best audio track (the shared ranking), unless VLC already has it. */
    func chooseBestAudioTrack() {
        guard let media else { return }
        let tracks = AudioTrackRanking.tracks(media)
        let best = AudioTrackRanking.best(tracks, current: currentAudioTrackIndex)
        #if DEBUG
        NSLog("Audio: \(tracks.map { "\($0.id) \($0.language ?? "?") \($0.format?.title ?? "?") \($0.channels)ch \($0.bitrate) \($0.name)" }), VLC chose \(currentAudioTrackIndex) → best \(best.map(String.init) ?? "-")")
        #endif
        guard tracks.count > 1, let best, best != currentAudioTrackIndex else { return }
        currentAudioTrackIndex = best
    }

    private static func tracks(names: [Any], indexes: [Any]) -> [MediaTrack] {
        zip(names, indexes).compactMap { name, index in
            guard let i = (index as? NSNumber)?.int32Value else { return nil }
            return MediaTrack(index: i, name: name as? String ?? "Track \(i)")
        }
    }
}

extension VLCMediaPlayer {
    /** Within a second of the media's end (VLC can stop there paused, not Ended). */
    var isAtEnd: Bool {
        let length = Int64(media?.length.intValue ?? 0)
        return length > 0 && Int64(time.intValue) >= length - 1_000
    }
}

/** A speed as the menus show it: "0.5×", "1×", "1.25×". */
func speedLabel(_ rate: Float) -> String {
    let text = rate == rate.rounded() ? String(Int(rate)) : String(format: "%g", rate)
    return text + "×"
}
