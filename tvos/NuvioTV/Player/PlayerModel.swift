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
    /// Kept in step with the screensaver: mpv draws into its own layer, so tvOS can't tell that a
    /// video is playing (as it can with AVPlayer) and would start the screensaver mid-film.
    @Published private(set) var isPaused = false { didSet { updateIdleTimer() } }
    private var isActive = false { didSet { updateIdleTimer() } }

    /// No screensaver while a video plays; paused or closed, the normal timeout applies again.
    private func updateIdleTimer() {
        let keepAwake = isActive && !isPaused
        if UIApplication.shared.isIdleTimerDisabled != keepAwake {
            UIApplication.shared.isIdleTimerDisabled = keepAwake
        }
    }
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
    /// Subtitle brightness 0–100 %, applied at once and kept as the default.
    @Published var subtitleBrightness: Int = 100 {
        didSet {
            controller.setSubtitleBrightness(subtitleBrightness, color: subtitleColor)
            settings?.subtitleBrightness = Int32(subtitleBrightness)
        }
    }
    private var subtitleColor = "#FFFFFF"
    /// Dialogue enhancer level (0 off … 3 high), applied at once and kept as the default.
    @Published var dialogueBoost: Int = 0 {
        didSet {
            controller.setDialogueBoost(dialogueBoost)
            settings?.dialogueBoost = Int32(dialogueBoost)
        }
    }
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

    /// Segments skipped automatically in this file (once each, so seeking back into one plays it).
    private var autoSkipped: Set<String> = []
    /// Briefly shown after an automatic skip ("Skipped intro").
    @Published private(set) var autoSkipNotice: String?
    private var noticeTask: Task<Void, Never>?

    /// Settings → Player → "Auto-skip intros and recaps" / "Auto-skip credits": the segment is
    /// skipped as soon as playback enters it. Skipping an episode's credits ends it (the next one
    /// starts when auto-play is on); a movie's go to its post-credits scene when there is one.
    private func autoSkipIfNeeded(_ segment: SkipSegment?) {
        guard let segment, let settings, settings.skipIntroEnabled else { return }
        let isCredits = segment.type == "outro" || segment.type == "movie-credits"
        let wanted = isCredits ? settings.autoSkipCredits : settings.autoSkipIntro
        guard wanted else { return }
        let key = "\(segment.type)|\(segment.startSeconds)"
        guard !autoSkipped.contains(key) else { return }
        autoSkipped.insert(key)
        skip(segment)
        switch segment.type {
        case "recap": autoSkipNotice = "Skipped recap"
        case "outro", "movie-credits":
            autoSkipNotice = segment.toPostCredits ? "Skipped to post-credits scene" : "Skipped credits"
        default: autoSkipNotice = "Skipped intro"
        }
        noticeTask?.cancel()
        noticeTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard !Task.isCancelled else { return }
            self?.autoSkipNotice = nil
        }
    }

    weak var window: UIWindow?
    /// Held strongly: by the time the player closes its view has already left the window.
    private var displayManager: AVDisplayManager?
    /// With Match Content on, a new file starts paused on its first frame until the TV has
    /// switched to the video's frame rate / dynamic range (no playback at the old rate, then a
    /// black flash mid-scene).
    private var holdingForDisplay = false
    private var displayHoldTask: Task<Void, Never>?

    private var playerWindow: UIWindow? {
        window ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
    }
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
        if segment != activeSkipSegment {
            activeSkipSegment = segment
            autoSkipIfNeeded(segment)
        }
        var prompt = false
        if let settings, nextVideo != nil, !nextPromptDismissed, duration > 60 {
            prompt = remaining <= Double(settings.nextEpisodePromptSeconds) && remaining > 0
        }
        if prompt != showNextPrompt { showNextPrompt = prompt }
    }

    // MARK: Lifecycle

    func start(_ request: PlaybackRequest, core: NuvioCore) {
        isActive = true
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
            subtitleBrightness: Int(s.subtitleBrightness),
            subtitleBorderColor: s.subtitleOutlineColor,
            subtitleBorderSize: Double(s.subtitleOutlineWidth) * 1.5,
            subtitleBackColor: s.subtitleBackgroundColor,
            subtitlePosition: 100 - Int(s.subtitleVerticalOffset),
            overrideAss: s.overrideAssStyle,
            subtitleDelay: Double(s.subtitleDelayMs) / 1000.0,
            audioDelay: Double(s.audioDelayMs) / 1000.0,
            dialogueBoost: Int(s.dialogueBoost)
        )
        subtitleScale = options.subtitleScale
        subtitleColor = options.subtitleColor
        subtitleBrightness = options.subtitleBrightness
        dialogueBoost = options.dialogueBoost
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
        autoSkipped = []
        subtitleMemoryDone = false
        addonSubtitlesSettled = false
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
        displayHoldTask?.cancel()
        holdingForDisplay = playerWindow?.avDisplayManager.isDisplayCriteriaMatchingEnabled == true
        // Paused before loading, so the first frame waits for the display switch.
        controller.setPaused(holdingForDisplay)
        controller.load(MPVLoadOptions(
            url: url,
            headers: request.headers,
            startSeconds: Double(request.startPositionMs) / 1000.0,
            title: request.title
        ))
        if holdingForDisplay {
            // Never hold forever (no video track, stalled decode, …).
            displayHoldTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 12_000_000_000)
                guard !Task.isCancelled else { return }
                self?.endDisplayHold()
            }
        } else {
            controller.play()
        }
        if aspectMode != .fit { controller.setAspect(aspectMode) }
        Task { await loadSubtitles(for: request) }
    }

    func stop() {
        isActive = false
        saveProgress(force: true)
        if let request { scrobble("stop", for: request) }
        streamSubscription?.cancel()
        streamSubscription = nil
        displayHoldTask?.cancel()
        holdingForDisplay = false
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
        // Give mpv a moment to list the added files, then settle the remembered choice.
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        guard self.request?.id == request.id else { return }
        addonSubtitlesSettled = true
        applyRememberedSubtitle()
    }

    private func rank(_ lang: String, _ preferred: [String]) -> Int {
        let code = SubtitleLanguages.normalize(lang)
        if let index = preferred.firstIndex(where: { SubtitleLanguages.normalize($0) == code }) { return index }
        return preferred.count
    }

    // MARK: Controls

    func togglePause() {
        if holdingForDisplay { endDisplayHold(); return }
        let paused = !isPaused
        isPaused = paused // optimistic, so the button flips instantly
        controller.setPaused(paused)
    }

    func seek(by seconds: Double) {
        // Live TV can't be seeked (it crashed some streams).
        guard request?.isLive != true, seconds.isFinite else { return }
        controller.seek(by: seconds)
        position = min(max(0, position + seconds), duration)
        clock.position = position
    }

    func seek(to seconds: Double) {
        guard request?.isLive != true, seconds.isFinite else { return }
        controller.seek(to: seconds)
        position = seconds
        clock.position = seconds
        updateDerivedState()
    }

    func selectAudio(_ track: MPVTrack) { controller.selectAudio(track.id) }

    /// The viewer's pick in the Subtitles panel; remembered for this series / movie when enabled.
    func selectSubtitle(_ track: MPVTrack?) {
        controller.selectSubtitle(track?.id)
        subtitleMemoryDone = true
        guard settings?.rememberSubtitlePerTitle == true, let request, request.tracksProgress, let core else { return }
        core.setSubtitleChoice(contentId: request.meta.id, choice: SubtitleChoice(track: track).encoded)
    }

    // MARK: Remembered subtitle per series / movie

    /// Set once the remembered choice was applied (or the viewer picked one) for this file.
    private var subtitleMemoryDone = false
    /// Add-on subtitles have been added (or there are none), so every candidate is listed.
    private var addonSubtitlesSettled = false

    /// Picks the subtitle that best matches the one remembered for this title: at once when a
    /// perfect match is listed, else once add-on subtitles have arrived (best acceptable match, or
    /// mpv's own pick when nothing fits).
    private func applyRememberedSubtitle() {
        guard !subtitleMemoryDone, settings?.rememberSubtitlePerTitle == true,
              let request, request.tracksProgress, let core,
              let raw = core.subtitleChoice(contentId: request.meta.id),
              let choice = SubtitleChoice.decode(raw) else { return }
        if choice.off {
            controller.selectSubtitle(nil)
            subtitleMemoryDone = true
            return
        }
        let best = subtitleTracks
            .compactMap { track in choice.score(track).map { (track, $0) } }
            .max { $0.1 < $1.1 }
        if let best, best.1 >= choice.perfectScore || addonSubtitlesSettled {
            if !best.0.isSelected { controller.selectSubtitle(best.0.id) }
            subtitleMemoryDone = true
        } else if addonSubtitlesSettled {
            subtitleMemoryDone = true
        }
    }


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
            embedded: next.streams,
            serverVideoId: target.serverVideoId,
            serverReleased: next.released
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

    func mpvDidUpdate(_ rawSnapshot: MPVController.Snapshot) {
        // Live streams can report NaN / infinite times; keep them out of the maths and the UI.
        var snapshot = rawSnapshot
        if !snapshot.position.isFinite || snapshot.position < 0 { snapshot.position = 0 }
        if !snapshot.duration.isFinite || snapshot.duration < 0 { snapshot.duration = 0 }
        if !snapshot.cacheSeconds.isFinite || snapshot.cacheSeconds < 0 { snapshot.cacheSeconds = 0 }
        // Backstop: if the playhead is moving, we're not buffering, whatever the flags say.
        let advancing = !snapshot.isPaused && snapshot.position > position + 0.05
        // While waiting for the display switch it shows as loading, not as paused.
        let buffering = holdingForDisplay || (snapshot.isBuffering && !advancing)
        let paused = snapshot.isPaused && !holdingForDisplay
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
        if isPaused != paused { isPaused = paused }
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
        applyRememberedSubtitle()
    }

    func mpvVideoParamsChanged(fps: Double, gamma: String?, primaries: String?, frameReady: Bool) {
        // During the hold, wait for the first decoded frame so SDR / HDR is known: one switch, not two.
        if holdingForDisplay && !frameReady { return }
        let manager = DisplayCriteria.apply(fps: fps, gamma: gamma, in: playerWindow)
        if let manager { displayManager = manager }
        guard holdingForDisplay else { return }
        displayHoldTask?.cancel()
        displayHoldTask = Task { @MainActor [weak self] in
            if let manager {
                // The switch starts shortly after the criteria change (none if the TV is already
                // in that mode); then wait for it to finish, and a moment for the TV to show picture.
                var waited = 0
                while !manager.isDisplayModeSwitchInProgress && waited < 8 {
                    try? await Task.sleep(nanoseconds: 100_000_000)
                    waited += 1
                }
                var switching = 0
                while manager.isDisplayModeSwitchInProgress && switching < 100 {
                    try? await Task.sleep(nanoseconds: 100_000_000)
                    switching += 1
                }
                if switching > 0 { try? await Task.sleep(nanoseconds: 600_000_000) }
            }
            guard !Task.isCancelled else { return }
            self?.endDisplayHold()
        }
    }

    /// Starts playback after the display switch (or the safety timeout, or the user pressing play).
    private func endDisplayHold() {
        guard holdingForDisplay else { return }
        holdingForDisplay = false
        displayHoldTask?.cancel()
        displayHoldTask = nil
        controller.play()
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
