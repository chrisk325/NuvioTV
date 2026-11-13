import Foundation
import NuvioShared

/// Everything the player needs for one video. Built by StreamsView (or by auto-next).
struct PlaybackRequest: Identifiable {
    let id = UUID()
    let url: String
    let headers: [String: String]
    let meta: Meta
    let video: Video?
    let videoId: String
    let title: String
    let startPositionMs: Int64
    var streamName: String = ""
    var bingeGroup: String? = nil
    var streamSubtitles: [Subtitle] = []
    var videoHash: String? = nil
    var videoSize: Int64 = 0
    var filename: String? = nil
    /** Live TV: no progress, subtitles lookup, scrobbling or next episode; up/down zaps channels. */
    var isLive = false
    var liveChannels: [LiveTvChannel] = []
    var liveIndex = 0
    var isTrailer = false

    /** Progress, scrobbling and add-on subtitles apply to real titles only. */
    var tracksProgress: Bool { !isLive && !isTrailer }

    /// The episode after this one (watchable episodes only), for auto-next.
    var nextVideo: Video? {
        guard tracksProgress, let video else { return nil }
        let episodes = meta.watchableEpisodes()
        guard let index = episodes.firstIndex(where: { $0.id == video.id }), index + 1 < episodes.count else { return nil }
        return episodes[index + 1]
    }

    static func make(stream: AddonStream, target: StreamTarget, resume: WatchProgress?) -> PlaybackRequest? {
        guard let url = stream.directPlaybackUrl else { return nil }
        let hints = stream.behaviorHints
        return PlaybackRequest(
            url: url,
            headers: stream.requestHeaders,
            meta: target.meta,
            video: target.video,
            videoId: target.videoId,
            title: target.title,
            startPositionMs: (resume?.isResumable == true) ? (resume?.positionMs ?? 0) : 0,
            streamName: stream.getDisplayName(),
            bingeGroup: hints?.bingeGroup,
            streamSubtitles: stream.subtitles,
            videoHash: hints?.videoHash,
            videoSize: hints?.videoSize?.int64Value ?? 0,
            filename: hints?.filename
        )
    }
}

/// Picks the stream to continue with for the next episode: same binge group first
/// (Android StreamAutoPlaySelector behaviour), then the auto-play regex, then the first playable.
enum StreamAutoPicker {
    static func pick(from groups: [AddonStreams], bingeGroup: String?, regex: String) -> AddonStream? {
        let streams = groups.flatMap(\.streams).filter(\.isDirectlyPlayable)
        if let bingeGroup, !bingeGroup.isEmpty,
           let match = streams.first(where: { $0.behaviorHints?.bingeGroup == bingeGroup }) {
            return match
        }
        if let pattern = try? NSRegularExpression(pattern: regex, options: [.caseInsensitive]), !regex.isEmpty,
           let match = streams.first(where: { stream in
               let text = [stream.name, stream.title, stream.description_].compactMap { $0 }.joined(separator: " ")
               return pattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
           }) {
            return match
        }
        return streams.first
    }

    static func matchesRegex(_ stream: AddonStream, regex: String) -> Bool {
        guard !regex.isEmpty, let pattern = try? NSRegularExpression(pattern: regex, options: [.caseInsensitive]) else { return false }
        let text = [stream.name, stream.title, stream.description_].compactMap { $0 }.joined(separator: " ")
        return pattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }
}
