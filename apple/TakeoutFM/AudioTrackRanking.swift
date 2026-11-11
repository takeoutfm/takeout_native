import Foundation
import Shared
#if os(macOS)
import VLCKit
#else
import MobileVLCKit
#endif

/**
 * VLC's audio tracks for the shared rules: the best-track choice (`bestAudioTrack`, as on the
 * phone, TV and Linux) and the picker labels (`audioTrackLabel`). VLCKit doesn't say which track
 * the file marks as default, so the one VLC picked stands in for it.
 */
enum AudioTrackRanking {
    struct Track {
        let id: Int32
        let language: String?
        let name: String
        let format: AudioFormat?
        let channels: Int32
        let bitrate: Int32
    }

    private static let preferenceKey = "audioLanguage"

    /** Remembers [language] for the next title (when the viewer picks a track themselves). */
    static func remember(_ language: String?) {
        guard let language = AudioTrackRankingKt.normalizeLanguage(tag: language) else { return }
        UserDefaults.standard.set(language, forKey: preferenceKey)
    }

    /** The audio tracks of [media], from VLC's track information. */
    static func tracks(_ media: VLCMedia) -> [Track] {
        media.tracksInformation.compactMap { info -> Track? in
            guard let info = info as? [String: Any],
                  info[VLCMediaTracksInformationType] as? String == VLCMediaTracksInformationTypeAudio,
                  let id = (info[VLCMediaTracksInformationId] as? NSNumber)?.int32Value else { return nil }
            let fourcc = (info[VLCMediaTracksInformationCodec] as? NSNumber)?.uint32Value ?? 0
            let codec = VLCMedia.codecName(forFourCC: fourcc, trackType: VLCMediaTracksInformationTypeAudio)
            return Track(
                id: id,
                language: info[VLCMediaTracksInformationLanguage] as? String,
                name: info[VLCMediaTracksInformationDescription] as? String ?? "",
                format: AudioTrackLabelKt.audioFormat(codec: codec, profile: ""),
                channels: (info[VLCMediaTracksInformationAudioChannelsNumber] as? NSNumber)?.int32Value ?? 0,
                bitrate: (info[VLCMediaTracksInformationBitrate] as? NSNumber)?.int32Value ?? 0
            )
        }
    }

    /** The id of the best of [tracks], or nil when there are none; [current] is VLC's pick. */
    static func best(_ tracks: [Track], current: Int32) -> Int32? {
        let preferred = UserDefaults.standard.string(forKey: preferenceKey) ?? Locale.preferredLanguages.first
        let candidates = tracks.map { track in
            AudioCandidate(
                language: track.language,
                label: track.name,
                codec: track.format?.codec ?? .other,
                channels: track.channels,
                bitrate: track.bitrate,
                isDefault: track.id == current,
                isCommentary: false  // the shared ranking looks for "commentary" in the label
            )
        }
        let best = Int(AudioTrackRankingKt.bestAudioTrack(candidates: candidates, preferredLanguage: preferred))
        return tracks.indices.contains(best) ? tracks[best].id : nil
    }

    /** "Dolby Digital · Surround 5.1" over "English"; [number] (from 1) names an unknown track. */
    static func label(_ track: Track, number: Int) -> AudioTrackLabel {
        let languageName = AudioTrackRankingKt.normalizeLanguage(tag: track.language)
            .map { Locale.current.localizedString(forLanguageCode: $0) ?? $0 }
        return AudioTrackLabelKt.audioTrackLabel(
            format: track.format,
            channels: track.channels,
            languageName: languageName,
            name: track.name,
            isCommentary: false,
            number: Int32(number)
        )
    }
}
