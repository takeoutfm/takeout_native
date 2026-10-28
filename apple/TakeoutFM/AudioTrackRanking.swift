import Foundation
import VLCKit

/**
 * The phone's best-audio-track choice (`:android-media` `bestAudioTrack`), for VLC's tracks:
 * 1. the preferred language (the last one picked, else the Mac's), when any track has it;
 * 2. not commentary;
 * 3. most channels;
 * 4. better codec at equal channels (TrueHD > E-AC-3 > DTS > AC-3 > FLAC/PCM > the rest);
 * 5. higher bitrate.
 * VLCKit doesn't say which track the file marks as default, so with no language match the
 * track VLC picked stays first among equals.
 */
enum AudioTrackRanking {
    struct Candidate {
        let id: Int32
        let language: String?
        let description: String
        let codec: String
        let channels: Int
        let bitrate: Int
    }

    private static let preferenceKey = "audioLanguage"

    /** Remembers [language] for the next title (when the viewer picks a track themselves). */
    static func remember(_ language: String?) {
        guard let language = normalize(language) else { return }
        UserDefaults.standard.set(language, forKey: preferenceKey)
    }

    /** The audio tracks of [media], from VLC's track information. */
    static func candidates(_ media: VLCMedia) -> [Candidate] {
        media.tracksInformation.compactMap { info -> Candidate? in
            guard let info = info as? [String: Any],
                  info[VLCMediaTracksInformationType] as? String == VLCMediaTracksInformationTypeAudio,
                  let id = (info[VLCMediaTracksInformationId] as? NSNumber)?.int32Value else { return nil }
            let fourcc = (info[VLCMediaTracksInformationCodec] as? NSNumber)?.uint32Value ?? 0
            return Candidate(
                id: id,
                language: info[VLCMediaTracksInformationLanguage] as? String,
                description: info[VLCMediaTracksInformationDescription] as? String ?? "",
                codec: VLCMedia.codecName(forFourCC: fourcc, trackType: VLCMediaTracksInformationTypeAudio),
                channels: (info[VLCMediaTracksInformationAudioChannelsNumber] as? NSNumber)?.intValue ?? 0,
                bitrate: (info[VLCMediaTracksInformationBitrate] as? NSNumber)?.intValue ?? 0
            )
        }
    }

    /** The id of the best of [candidates], or nil when there are none; [current] wins ties. */
    static func best(_ candidates: [Candidate], current: Int32) -> Int32? {
        let wanted = normalize(UserDefaults.standard.string(forKey: preferenceKey))
            ?? normalize(Locale.preferredLanguages.first)
        let anyMatches = wanted != nil && candidates.contains { normalize($0.language) == wanted }
        func key(_ c: Candidate) -> [Int] {
            [
                anyMatches ? (normalize(c.language) == wanted ? 0 : 1) : 0,
                c.description.localizedCaseInsensitiveContains("commentary") ? 1 : 0,
                -max(c.channels, 0),
                -codecRank(c.codec),
                -max(c.bitrate, 0),
                c.id == current ? 0 : 1,
            ]
        }
        return candidates.min { key($0).lexicographicallyPrecedes(key($1)) }?.id
    }

    /** e.g. "English · 5.1 · AC-3", as the phone's track picker; the file's own name when it adds something. */
    static func label(_ c: Candidate) -> String {
        var parts: [String] = []
        if let code = normalize(c.language) {
            parts.append(Locale.current.localizedString(forLanguageCode: code) ?? code)
        }
        switch c.channels {
        case 1: parts.append("Mono")
        case 2: parts.append("Stereo")
        case 6: parts.append("5.1")
        case 8: parts.append("7.1")
        case let n where n > 0: parts.append("\(n) ch")
        default: break
        }
        parts.append(shortCodec(c.codec))
        if c.description.localizedCaseInsensitiveContains("commentary") { parts.append("Commentary") }
        return parts.joined(separator: " · ")
    }

    private static func shortCodec(_ name: String) -> String {
        let n = name.lowercased()
        if n.contains("truehd") || n.contains("mlp") { return "TrueHD" }
        if n.contains("e-ac3") || n.contains("e-ac-3") || n.contains("eac3") { return "E-AC-3" }
        if n.contains("dts") { return "DTS" }
        if n.contains("a52") || n.contains("ac3") || n.contains("ac-3") { return "AC-3" }
        if n.contains("aac") { return "AAC" }
        if n.contains("flac") { return "FLAC" }
        if n.contains("opus") { return "Opus" }
        if n.contains("mpeg") { return "MP3" }
        return name
    }

    /** "eng", "en-US" and "en" all compare equal; "und"/blank mean unknown. */
    static func normalize(_ tag: String?) -> String? {
        guard let tag = tag?.trimmingCharacters(in: .whitespaces), !tag.isEmpty, tag != "und" else { return nil }
        let code = Locale(identifier: tag.replacingOccurrences(of: "_", with: "-")).language.languageCode
        return (code?.identifier(.alpha2) ?? code?.identifier ?? tag).lowercased()
    }

    private static func codecRank(_ name: String) -> Int {
        let n = name.lowercased()
        if n.contains("truehd") || n.contains("mlp") { return 6 }
        if n.contains("e-ac3") || n.contains("e-ac-3") || n.contains("eac3") { return 4 }
        if n.contains("dts") { return 3 }
        if n.contains("a52") || n.contains("ac3") || n.contains("ac-3") { return 2 }
        if n.contains("flac") || n.contains("pcm") { return 2 }
        return 1
    }
}
