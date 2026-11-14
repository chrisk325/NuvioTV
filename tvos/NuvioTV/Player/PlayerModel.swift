import AVKit
import Foundation
import NuvioShared
import UIKit

/// The fast-changing playback clock (several updates a second). Only the seek bar and time
/// labels observe it, so the rest of the player UI doesn't re-render while video plays.
@MainActor
final class PlaybackClock: ObservableObject {
    @Published fileprivate(set) var position: Double = 0
    @Published fileprivate(set) var duration: Double = 0
    @Published fileprivate(set) var cacheSeconds: Double = 0

    var remaining: Double { max(0, duration - position) }
}

/// Drives mpv for one playback session: options from AppSettings, add-on subtitles,
/// progress saving, track selection, delays and auto-next episode.
@MainActor
final class PlayerModel: ObservableObject, MPVControllerDelegate {
    let controller = MPVController()
    let clock = PlaybackClock()

    @Published private(set) var request: PlaybackRequest?
    // Plain (unpublished) copies for logic; the UI reads `clock`.
    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    @Published private(set) var hasDuration = false
    @Published private(set) var isPaused = false
    @Published private(set) var isBuffering = true
    @Published private(set) var speed: Double = 1
    @Published private(set) var audioTracks: [MPVTrack] = []
    @Published private(set) var subtitleTracks: [MPVTrack] = []
    @Published private(set) var errorMessage: String?
    @Published private(set) var addonSubtitleCount = 0
    @Published private(set) var isLoadingAddonSubtitles = false
    @Published var subtitleDelay: Double = 0 { didSet { controller.setSubtitleDelay(subtitleDelay) } }
    @Published var audioDelay: Double = 0 { didSet { controller.setAudioDelay(audioDelay) } }
    @Published var subtitleScale: Double = 1 { didSet { controller.setSubtitleScale(subtitleScale) } }
    @Published private(set) var nextVideo: Video?
    @Published private(set) var isResolvingNext = false
    @Published var nextPromptDismissed = false { didSet { updateDerivedState() } }
    private var skipSegments: [SkipSegment] = []
    private var skipLoadedFor: UUID?

    /// The skip segment the playhead is in (published only when it changes).
    @Published private(set) var activeSkipSegment: SkipSegment?
    /// Whether the "Up next" card shows (published only when it changes).
    @Published private(set) var showNextPrompt = false

    func skip(_ segment: SkipSegment) {
        seek(to: segment.endSeconds)
    }

    weak var window: UIWindow?
    /// Held strongly: by the time the player closes its view has already left the window.
    private var displayManager: AVDisplayManager?
    private var core: NuvioCore?
    private var settings: AppSettings?
    private var lastSavedAt = Date.distantPast
    private var streamSubscription: CoreSubscription?
    private var finished = false
    private var started = false
    private var scrobbledStart = false
    private var lastPausedState = false

    var remaining: Double { max(0, duration - position) }

    private func updateDerivedState() {
        let segment = skipSegments.first { position >= $0.startSeconds && position < $0.endSeconds - 1 }
        if segment != activeSkipSegment { activeSkipSegment = segment }
        var prompt = false
        if let settings, nextVideo != nil, !nextPromptDismissed, duration > 60 {
            prompt = remaining <= Double(settings.nextEpisodePromptSeconds) && remaining > 0
        }
        if prompt != showNextPrompt { showNextPrompt = prompt }
    }

    // MARK: Lifecycle

    func start(_ request: PlaybackRequest, core: NuvioCore) {
        guard !started else { return }
        started = true
        self.core = core
        self.settings = core.settings
        let s = core.settings
        let options = MPVSetupOptions(
            hardwareDecoding: s.hardwareDecoding,
            hdrPassthrough: s.hdrPassthrough,
            cacheSeconds: Int(s.cacheSeconds),
            audioLanguages: TrackLanguages.mpvList(TrackLanguages.audio(
                preferred: s.preferredAudioLanguage,
                secondary: s.secondaryAudioLanguage,
                originalLanguage: request.meta.language
            )),
            subtitleLanguages: TrackLanguages.mpvList(
                [s.subtitleLanguage, s.secondarySubtitleLanguage].compactMap { TrackLanguages.normalize($0) }
            ),
            subtitlesEnabled: s.subtitlesEnabled,
            useForcedSubtitles: s.useForcedSubtitles,
            subtitleScale: Double(s.subtitleSize) / 100.0,
            subtitleBold: s.subtitleBold,
            subtitleColor: s.subtitleTextColor,
            subtitleBorderColor: s.subtitleOutlineColor,
            subtitleBorderSize: Double(s.subtitleOutlineWidth) * 1.5,
            subtitleBackColor: s.subtitleBackgroundColor,
            subtitlePosition: 100 - Int(s.subtitleVerticalOffset),
            overrideAss: s.overrideAssStyle,
            subtitleDelay: Double(s.subtitleDelayMs) / 1000.0,
            audioDelay: Double(s.audioDelayMs) / 1000.0
        )
        subtitleScale = options.subtitleScale
        subtitleDelay = options.subtitleDelay
        audioDelay = options.audioDelay
        controller.delegate = self
        guard controller.setup(options) else {
            errorMessage = "The video engine failed to start."
            return
        }
        play(request)
    }

    private func play(_ request: PlaybackRequest) {
        guard let url = URL(string: request.url) else {
            errorMessage = "Invalid stream URL."
            return
        }
        if let previous = self.request, previous.id != request.id { scrobble("stop", for: previous) }
        self.request = request
        skipSegments = []
        activeSkipSegment = nil
        showNextPrompt = false
        scrobbledStart = false
        finished = false
        nextPromptDismissed = false
        errorMessage = nil
        position = Double(request.startPositionMs) / 1000.0
        clock.position = position
        nextVideo = request.nextVideo
        audioTracks = []
        subtitleTracks = []
        controller.load(MPVLoadOptions(
            url: url,
            headers: request.headers,
            startSeconds: Double(request.startPositionMs) / 1000.0,
            title: request.title
        ))
        controller.play()
        if aspectMode != .fit { controller.setAspect(aspectMode) }
        Task { await loadSubtitles(for: request) }
    }

    func stop() {
        saveProgress(force: true)
        if let request { scrobble("stop", for: request) }
        streamSubscription?.cancel()
        streamSubscription = nil
        controller.pause()
        controller.destroy()
        DisplayCriteria.reset(displayManager)
        displayManager = nil
    }

    // MARK: Add-on subtitles

    private func loadSubtitles(for request: PlaybackRequest) async {
        guard let core, let settings, request.tracksProgress else { return }
        var list: [Subtitle] = request.streamSubtitles
        if settings.loadAddonSubtitles {
            isLoadingAddonSubtitles = true
            let fetched = (try? await core.subtitles(
                type: request.meta.apiType,
                contentId: request.meta.id,
                videoId: request.video?.id ?? request.videoId,
                videoHash: request.videoHash,
                videoSize: request.videoSize,
                filename: request.filename
            )) ?? []
            isLoadingAddonSubtitles = false
            list += fetched
        }
        guard self.request?.id == request.id else { return }
        // Preferred languages first; cap the count so the menu stays usable.
        let preferred = [settings.subtitleLanguage, settings.secondarySubtitleLanguage]
            .filter { !$0.isEmpty }
            .map { $0.lowercased() }
        let ranked = list.sorted { a, b in
            rank(a.lang, preferred) < rank(b.lang, preferred)
        }.prefix(60)
        for subtitle in ranked {
            let label = "\(SubtitleLanguages.name(subtitle.lang)) · \(subtitle.addonName)"
            controller.addSubtitle(url: subtitle.url, title: label, lang: subtitle.lang)
        }
        addonSubtitleCount = ranked.count
    }

    private func rank(_ lang: String, _ preferred: [String]) -> Int {
        let code = SubtitleLanguages.normalize(lang)
        if let index = preferred.firstIndex(where: { SubtitleLanguages.normalize($0) == code }) { return index }
        return preferred.count
    }

    // MARK: Controls

    func togglePause() {
        let paused = !isPaused
        isPaused = paused // optimistic, so the button flips instantly
        controller.setPaused(paused)
    }

    func seek(by seconds: Double) {
        controller.seek(by: seconds)
        position = min(max(0, position + seconds), duration)
        clock.position = position
    }

    func seek(to seconds: Double) {
        controller.seek(to: seconds)
        position = seconds
        clock.position = seconds
        updateDerivedState()
    }

    func selectAudio(_ track: MPVTrack) { controller.selectAudio(track.id) }

    func selectSubtitle(_ track: MPVTrack?) { controller.selectSubtitle(track?.id) }

    func setSpeed(_ value: Double) { controller.setSpeed(value) }

    /// Kept for the whole session, so the next episode keeps the same picture mode.
    @Published private(set) var aspectMode: PlayerAspectMode = .fit

    func setAspect(_ mode: PlayerAspectMode) {
        aspectMode = mode
        controller.setAspect(mode)
    }

    /// Persist the current delay/size as the new defaults.
    func rememberSubtitleTweaks() {
        settings?.subtitleDelayMs = Int32(subtitleDelay * 1000)
        settings?.audioDelayMs = Int32(audioDelay * 1000)
        settings?.subtitleSize = Int32(subtitleScale * 100)
    }

    // MARK: Live TV zapping

    @Published private(set) var isZapping = false

    func zap(by offset: Int) {
        guard let core, let request, request.isLive, !request.liveChannels.isEmpty, !isZapping else { return }
        let count = request.liveChannels.count
        let index = ((request.liveIndex + offset) % count + count) % count
        let channel = request.liveChannels[index]
        isZapping = true
        Task { @MainActor in
            defer { isZapping = false }
            do {
                let playback = try await core.liveTv.playback(channel: channel)
                var next = PlaybackRequest(
                    url: playback.url,
                    headers: playback.headers,
                    meta: core.liveTvMeta(channel: channel),
                    video: nil,
                    videoId: channel.id,
                    title: playback.title,
                    startPositionMs: 0
                )
                next.isLive = true
                next.liveChannels = request.liveChannels
                next.liveIndex = index
                play(next)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: Next episode

    func playNext() {
        guard let core, let request, let next = nextVideo, !isResolvingNext else { return }
        saveProgress(force: true, completed: true)
        isResolvingNext = true
        let target = StreamTarget(meta: request.meta, video: next)
        let regex = settings?.streamAutoPlayRegex ?? ""
        streamSubscription?.cancel()
        streamSubscription = core.loadStreams(
            type: request.meta.apiType,
            videoId: target.videoId,
            embedded: next.streams
        ) { [weak self] update in
            MainActor.assumeIsolated {
                guard let self, self.isResolvingNext else { return }
                // Prefer a binge-group match as soon as it shows up; otherwise wait for all add-ons.
                let bingeMatch = update.groups.flatMap(\.streams).first {
                    $0.isDirectlyPlayable && request.bingeGroup != nil && $0.behaviorHints?.bingeGroup == request.bingeGroup
                }
                guard bingeMatch != nil || update.isComplete else { return }
                self.streamSubscription?.cancel()
                self.streamSubscription = nil
                self.isResolvingNext = false
                guard let stream = bingeMatch ?? StreamAutoPicker.pick(from: update.groups, bingeGroup: request.bingeGroup, regex: regex),
                      let nextRequest = PlaybackRequest.make(stream: stream, target: target, resume: core.progressFor(videoId: target.videoId))
                else {
                    self.errorMessage = "No playable stream found for \(next.episodeLabel ?? "the next episode")."
                    return
                }
                self.play(nextRequest)
            }
        }
    }

    // MARK: Progress

    private func saveProgress(force: Bool = false, completed: Bool = false) {
        guard let core, let request, request.tracksProgress, duration > 0 else { return }
        if !force, Date().timeIntervalSince(lastSavedAt) < 10 { return }
        lastSavedAt = Date()
        let pos = (completed || finished) ? duration : position
        guard pos > 0 else { return }
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

    // MARK: Scrobbling (Trakt / Simkl / AniList via the shared core)

    private var progressPercent: Double {
        guard duration > 0 else { return 0 }
        return min(100, max(0, (finished ? duration : position) / duration * 100))
    }

    private func scrobble(_ action: String, for request: PlaybackRequest) {
        guard request.tracksProgress else { return }
        core?.reportPlayback(meta: request.meta, video: request.video, action: action, progressPercent: progressPercent)
    }

    // MARK: MPVControllerDelegate

    func mpvDidUpdate(_ snapshot: MPVController.Snapshot) {
        // Backstop: if the playhead is moving, we're not buffering, whatever the flags say.
        let advancing = !snapshot.isPaused && snapshot.position > position + 0.05
        let buffering = snapshot.isBuffering && !advancing
        if let request, snapshot.duration > 0 {
            if !scrobbledStart && !snapshot.isPaused && snapshot.position > 0 {
                scrobbledStart = true
                lastPausedState = false
                scrobble("start", for: request)
            } else if scrobbledStart && snapshot.isPaused != lastPausedState {
                lastPausedState = snapshot.isPaused
                scrobble(snapshot.isPaused ? "pause" : "start", for: request)
            }
        }
        position = snapshot.position
        duration = snapshot.duration
        clock.position = snapshot.position
        clock.duration = snapshot.duration
        clock.cacheSeconds = snapshot.cacheSeconds
        // Publish slow-changing state only when it actually changes.
        if hasDuration != (snapshot.duration > 0) { hasDuration = snapshot.duration > 0 }
        if isPaused != snapshot.isPaused { isPaused = snapshot.isPaused }
        if isBuffering != buffering { isBuffering = buffering }
        if speed != snapshot.speed { speed = snapshot.speed }
        updateDerivedState()
        if duration > 0 { saveProgress() }
        if let request, request.tracksProgress, duration > 0, skipLoadedFor != request.id, let core {
            skipLoadedFor = request.id
            Task { @MainActor in
                let segments = (try? await core.skipSegments(meta: request.meta, video: request.video, durationSeconds: duration)) ?? []
                if self.request?.id == request.id {
                    skipSegments = segments
                    updateDerivedState()
                }
            }
        }
    }

    func mpvTracksChanged(_ tracks: [MPVTrack]) {
        audioTracks = tracks.filter { $0.type == "audio" }
        subtitleTracks = tracks.filter { $0.type == "sub" }
    }

    func mpvVideoParamsChanged(fps: Double, gamma: String?, primaries: String?) {
        if let manager = DisplayCriteria.apply(fps: fps, gamma: gamma, in: window) {
            displayManager = manager
        }
    }

    func mpvDidReachEnd() {
        guard !finished else { return }
        finished = true
        saveProgress(force: true, completed: true)
        if settings?.autoPlayNextEpisode ?? true, nextVideo != nil {
            playNext()
        }
    }

    func mpvDidFail(_ message: String) {
        errorMessage = "mpv couldn't open this stream (\(message))."
    }
}

enum SubtitleLanguages {
    private static let iso2: [String: String] = [
        "eng": "en", "spa": "es", "fre": "fr", "fra": "fr", "ger": "de", "deu": "de", "ita": "it",
        "por": "pt", "pob": "pt", "rus": "ru", "jpn": "ja", "kor": "ko", "chi": "zh", "zho": "zh",
        "ara": "ar", "hin": "hi", "tur": "tr", "pol": "pl", "dut": "nl", "nld": "nl", "swe": "sv",
        "nor": "no", "dan": "da", "fin": "fi", "gre": "el", "ell": "el", "heb": "he", "cze": "cs",
        "ces": "cs", "hun": "hu", "rum": "ro", "ron": "ro", "ukr": "uk", "vie": "vi", "tha": "th",
        "ind": "id", "may": "ms", "msa": "ms", "hrv": "hr", "srp": "sr", "bul": "bg", "slv": "sl",
        "slo": "sk", "slk": "sk", "per": "fa", "fas": "fa", "ben": "bn", "tam": "ta", "tel": "te",
    ]

    static func normalize(_ code: String) -> String {
        let lower = code.lowercased().split(separator: "-").first.map(String.init) ?? code.lowercased()
        return iso2[lower] ?? lower
    }

    static func name(_ code: String) -> String {
        let normalized = normalize(code)
        return Locale.current.localizedString(forLanguageCode: normalized) ?? code
    }
}
