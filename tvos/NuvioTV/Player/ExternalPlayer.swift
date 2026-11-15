import SwiftUI
import UIKit
import NuvioShared

/// Third-party players a stream can be handed to by URL scheme (Settings > Player > Video engine).
enum ExternalPlayer {
    case infuse, vlc

    init?(engine: String) {
        switch engine {
        case AppSettings.companion.ENGINE_INFUSE: self = .infuse
        case AppSettings.companion.ENGINE_VLC: self = .vlc
        default: return nil
        }
    }

    var name: String {
        switch self {
        case .infuse: return "Infuse"
        case .vlc: return "VLC"
        }
    }

    /// Infuse: infuse://x-callback-url/play?url=…  VLC: vlc-x-callback://x-callback-url/stream?url=…
    /// with x-success / x-error / x-cancel pointing back at nuviotv://, so the player returns to
    /// Nuvio when playback finishes or fails.
    func url(for stream: String, startSeconds: Int = 0, filename: String? = nil) -> URL? {
        guard let encoded = Self.encode(stream) else { return nil }
        // Infuse: resume point (whole seconds) and a file name that helps it match metadata.
        var extras = ""
        if self == .infuse {
            if startSeconds > 0 { extras += "&position=\(startSeconds)" }
            if let filename, !filename.isEmpty, let name = Self.encode(filename) { extras += "&filename=\(name)" }
        }
        let callbacks = ["x-success": "nuviotv://x-callback-url/playback-finished",
                         "x-error": "nuviotv://x-callback-url/playback-error",
                         "x-cancel": "nuviotv://x-callback-url/playback-cancelled"]
            .sorted { $0.key < $1.key }
            .compactMap { key, value in Self.encode(value).map { "&\(key)=\($0)" } }
            .joined()
        switch self {
        case .infuse: return URL(string: "infuse://x-callback-url/play?url=\(encoded)\(extras)\(callbacks)")
        case .vlc: return URL(string: "vlc-x-callback://x-callback-url/stream?url=\(encoded)\(callbacks)")
        }
    }

    private static func encode(_ value: String) -> String? {
        value.addingPercentEncoding(withAllowedCharacters: queryValueAllowed)
    }

    /// Unreserved characters only, so the stream URL's own ?, & and = survive as one parameter.
    private static let queryValueAllowed: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~")
        return set
    }()
}

/// The title handed to Infuse / VLC, kept until the player calls back on nuviotv:// so the
/// position it reports can be saved as watch progress.
@MainActor
enum ExternalPlayback {
    private static var pending: PlaybackRequest?

    static func launched(_ request: PlaybackRequest, core: NuvioCore) {
        guard request.tracksProgress else { pending = nil; return }
        pending = request
        let percent = startPercent(request, core: core)
        core.reportPlayback(meta: request.meta, video: request.video, action: "start", progressPercent: percent)
    }

    /// nuviotv://x-callback-url/playback-finished?lastPlayedUrl=…&position=<seconds>[&progress=<0…1>]
    /// (Infuse 8.4.6+). Saves progress, marks the title watched when finished and stops the
    /// tracker scrobble. Returns whether the URL was a player callback.
    @discardableResult
    static func handle(_ url: URL, core: NuvioCore) -> Bool {
        guard url.scheme == "nuviotv", url.host == "x-callback-url" else { return false }
        guard let request = pending else { return true }
        pending = nil
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func number(_ names: String...) -> Double? {
            for name in names {
                if let raw = items.first(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame })?.value,
                   let value = Double(raw) { return value }
            }
            return nil
        }
        guard url.path.contains("finished") || url.path.contains("cancelled") else { return true }
        let position = number("position", "positionSeconds", "time") ?? 0
        let progress = number("progress", "percent").map { $0 > 1 ? $0 / 100 : $0 }
        let duration = number("duration", "durationSeconds")
            ?? progress.flatMap { $0 > 0.01 && $0 < 0.999 && position > 0 ? position / $0 : nil }
            ?? knownDuration(request, core: core)
        let finished = (progress ?? 0) >= 0.999 || (duration.map { $0 > 0 && position / $0 >= 0.92 } ?? false)

        if let duration, duration > 0 {
            let pos = finished ? duration : min(position, duration)
            if pos > 0 {
                core.saveProgress(
                    meta: request.meta,
                    videoId: request.videoId,
                    season: request.video?.season,
                    episode: request.video?.episode,
                    episodeTitle: request.video?.title,
                    positionMs: Int64(pos * 1000),
                    durationMs: Int64(duration * 1000)
                )
            }
            core.reportPlayback(meta: request.meta, video: request.video, action: "stop",
                                progressPercent: min(100, pos / duration * 100))
        } else if finished {
            core.setWatched(meta: request.meta, video: request.video, isWatched: true)
            core.reportPlayback(meta: request.meta, video: request.video, action: "stop", progressPercent: 100)
        }
        return true
    }

    private static func startPercent(_ request: PlaybackRequest, core: NuvioCore) -> Double {
        guard let duration = knownDuration(request, core: core), duration > 0 else { return 0 }
        return min(100, Double(request.startPositionMs) / 1000 / duration * 100)
    }

    /// Infuse doesn't report the length: use the last saved progress, else the episode / movie runtime.
    private static func knownDuration(_ request: PlaybackRequest, core: NuvioCore) -> Double? {
        if let saved = core.progressFor(videoId: request.videoId), saved.durationMs > 0 {
            return Double(saved.durationMs) / 1000
        }
        if let minutes = request.video?.runtimeMinutes, minutes > 0 { return Double(minutes) * 60 }
        if let runtime = request.meta.runtime, let minutes = parseMinutes(runtime) { return Double(minutes) * 60 }
        return nil
    }

    /// "2h53min", "2h 53m", "173 min", "45" → minutes.
    static func parseMinutes(_ text: String) -> Int? {
        let lower = text.lowercased()
        let numbers = lower.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        guard !numbers.isEmpty else { return nil }
        if lower.contains("h") {
            let hours = numbers[0]
            let minutes = numbers.count > 1 ? numbers[1] : 0
            return hours * 60 + minutes
        }
        return numbers[0]
    }
}

/// Hands the stream to the external app and closes; explains when the app isn't installed.
struct ExternalPlayerScreen: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    let player: ExternalPlayer
    let request: PlaybackRequest
    @State private var failed = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if failed {
                VStack(spacing: 30) {
                    MessageView(
                        title: "Couldn't open \(player.name)",
                        message: "Install \(player.name) from the App Store, or pick another video engine in Settings ▸ Player."
                    )
                    .frame(maxHeight: 400)
                    Button("Back") { dismiss() }
                }
            } else {
                LoadingView(label: "Opening in \(player.name)…")
            }
        }
        .task {
            let start = Int(request.startPositionMs / 1000)
            guard let url = player.url(for: request.url, startSeconds: start, filename: request.filename) else {
                failed = true; return
            }
            let opened = await UIApplication.shared.open(url)
            if opened {
                ExternalPlayback.launched(request, core: app.core)
                dismiss()
            } else {
                failed = true
            }
        }
    }
}
