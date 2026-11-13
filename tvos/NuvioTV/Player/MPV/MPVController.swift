import AVFoundation
import Foundation
import Libmpv
import UIKit

/// One audio / subtitle / video track as reported by mpv's `track-list`.
struct MPVTrack: Identifiable, Hashable {
    let id: Int
    let type: String          // "video" | "audio" | "sub"
    let title: String?
    let lang: String?
    let codec: String?
    let isSelected: Bool
    let isExternal: Bool
    let isForced: Bool
    let isDefault: Bool
    let channels: Int?

    /// Main line: language plus title, without repeating the language when the title already
    /// starts with it (add-on subtitles are titled "English · OpenSubtitles v3").
    var label: String {
        let language = lang.flatMap { code -> String? in
            guard !code.isEmpty else { return nil }
            return Locale.current.localizedString(forLanguageCode: code) ?? code
        }
        let cleanTitle = title?.trimmingCharacters(in: .whitespaces)
        switch (language, cleanTitle) {
        case let (language?, title?) where !title.isEmpty && title != lang:
            return title.lowercased().hasPrefix(language.lowercased()) ? title : "\(language) · \(title)"
        case let (language?, _):
            return language
        case (nil, let title?) where !title.isEmpty:
            return title
        default:
            return "Track \(id)"
        }
    }

    /// Secondary line: codec, channels, forced / external.
    var details: String? {
        var parts: [String] = []
        if let codec { parts.append(codec.uppercased()) }
        if let channels, channels > 0 { parts.append(Self.channelLabel(channels)) }
        if isForced { parts.append("Forced") }
        if isExternal { parts.append("External") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    var displayName: String {
        details.map { "\(label)  (\($0))" } ?? label
    }

    private static func channelLabel(_ count: Int) -> String {
        switch count {
        case 1: return "Mono"
        case 2: return "Stereo"
        case 6: return "5.1"
        case 8: return "7.1"
        default: return "\(count)ch"
        }
    }
}

/// Everything mpv needs to open a stream.
struct MPVLoadOptions {
    var url: URL
    var headers: [String: String] = [:]
    var startSeconds: Double = 0
    var title: String = ""
}

/// Settings applied before `mpv_initialize` (mirrors AppSettings in the shared core).
struct MPVSetupOptions {
    var hardwareDecoding = true
    var hdrPassthrough = true
    var cacheSeconds = 120
    var audioLanguages = ""        // "en,de"
    var subtitleLanguages = ""     // "en,es"
    var subtitlesEnabled = true
    var useForcedSubtitles = true
    var subtitleScale = 1.0
    var subtitleBold = false
    var subtitleColor = "#FFFFFF"
    var subtitleBorderColor = "#000000"
    var subtitleBorderSize = 2.0
    var subtitleBackColor = "#00000000"
    var subtitlePosition = 95      // 0 = top, 100 = bottom
    var overrideAss = false
    var subtitleDelay = 0.0        // seconds
    var audioDelay = 0.0           // seconds
}

protocol MPVControllerDelegate: AnyObject {
    @MainActor func mpvDidUpdate(_ snapshot: MPVController.Snapshot)
    @MainActor func mpvTracksChanged(_ tracks: [MPVTrack])
    @MainActor func mpvVideoParamsChanged(fps: Double, gamma: String?, primaries: String?)
    @MainActor func mpvDidReachEnd()
    @MainActor func mpvDidFail(_ message: String)
}

/// Thin Swift wrapper around libmpv rendering into a CAMetalLayer through MoltenVK (vo=gpu-next),
/// following MPVKit's tvOS demo. All mpv calls are thread-safe; events are read on `queue`.
final class MPVController {
    struct Snapshot {
        var position: Double = 0
        var duration: Double = 0
        var isPaused = false
        var isBuffering = true
        var cacheSeconds: Double = 0
        var speed: Double = 1
    }

    let metalLayer = MPVMetalLayer()
    weak var delegate: MPVControllerDelegate?

    private var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "nuvio.mpv.events", qos: .userInitiated)
    private var snapshot = Snapshot()
    private var didStartPlayback = false
    // Buffering = loading a file, seeking, or waiting on the network cache (mpv's own signals).
    private var waitingForFile = true
    private var seeking = false
    private var pausedForCache = false

    private func updateBuffering() {
        snapshot.isBuffering = waitingForFile || seeking || pausedForCache
    }
    private var lastPublish = Date.distantPast

    init() {
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
    }

    deinit {
        destroy()
    }

    // MARK: Lifecycle

    func setup(_ options: MPVSetupOptions) -> Bool {
        guard handle == nil, let mpv = mpv_create() else { return handle != nil }
        handle = mpv

#if DEBUG
        mpv_request_log_messages(mpv, "warn")
#else
        mpv_request_log_messages(mpv, "error")
#endif
        var layerRef = metalLayer
        mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &layerRef)

        set(option: "vo", "gpu-next")
        set(option: "gpu-api", "vulkan")
        set(option: "gpu-context", "moltenvk")
        set(option: "hwdec", options.hardwareDecoding ? "videotoolbox" : "no")
        set(option: "video-rotate", "no")
        set(option: "keep-open", "yes")
        set(option: "idle", "yes")
        set(option: "target-colorspace-hint", options.hdrPassthrough ? "yes" : "no")
        set(option: "hdr-compute-peak", "auto")

        // Networking / cache (debrid & CDN streams).
        set(option: "cache", "yes")
        set(option: "cache-secs", String(options.cacheSeconds))
        set(option: "demuxer-max-bytes", "400MiB")
        set(option: "demuxer-max-back-bytes", "100MiB")
        set(option: "demuxer-readahead-secs", "20")
        set(option: "network-timeout", "30")
        set(option: "user-agent", "Mozilla/5.0 (AppleTV) NuvioTV")
        set(option: "stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")

        // Audio / subtitles (libass renders ASS/SSA with full typesetting).
        if !options.audioLanguages.isEmpty { set(option: "alang", options.audioLanguages) }
        if !options.subtitleLanguages.isEmpty { set(option: "slang", options.subtitleLanguages) }
        set(option: "subs-with-matching-audio", options.useForcedSubtitles ? "forced" : "yes")
        set(option: "subs-fallback", options.subtitlesEnabled ? "yes" : "no")
        set(option: "subs-fallback-forced", options.useForcedSubtitles ? "always" : "no")
        if !options.subtitlesEnabled { set(option: "sid", "no") }
        set(option: "sub-auto", "no")
        set(option: "sub-codepage", "auto")
        set(option: "embeddedfonts", "yes")
        set(option: "sub-scale", String(format: "%.2f", options.subtitleScale))
        set(option: "sub-bold", options.subtitleBold ? "yes" : "no")
        set(option: "sub-color", options.subtitleColor)
        set(option: "sub-border-color", options.subtitleBorderColor)
        set(option: "sub-border-size", String(format: "%.1f", options.subtitleBorderSize))
        set(option: "sub-back-color", options.subtitleBackColor)
        set(option: "sub-pos", String(options.subtitlePosition))
        set(option: "sub-ass-override", options.overrideAss ? "force" : "scale")
        set(option: "sub-delay", String(format: "%.3f", options.subtitleDelay))
        set(option: "audio-delay", String(format: "%.3f", options.audioDelay))
        set(option: "audio-channels", "auto-safe")

        guard mpv_initialize(mpv) >= 0 else {
            mpv_terminate_destroy(mpv)
            handle = nil
            return false
        }

        mpv_observe_property(mpv, 0, "time-pos", MPV_FORMAT_DOUBLE)
        mpv_observe_property(mpv, 0, "duration", MPV_FORMAT_DOUBLE)
        mpv_observe_property(mpv, 0, "pause", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "core-idle", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "seeking", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "demuxer-cache-duration", MPV_FORMAT_DOUBLE)
        mpv_observe_property(mpv, 0, "eof-reached", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "speed", MPV_FORMAT_DOUBLE)
        mpv_observe_property(mpv, 0, "track-list/count", MPV_FORMAT_INT64)
        mpv_observe_property(mpv, 0, "aid", MPV_FORMAT_STRING)
        mpv_observe_property(mpv, 0, "sid", MPV_FORMAT_STRING)
        mpv_observe_property(mpv, 0, "video-params/gamma", MPV_FORMAT_STRING)
        mpv_observe_property(mpv, 0, "container-fps", MPV_FORMAT_DOUBLE)

        mpv_set_wakeup_callback(mpv, { ctx in
            guard let ctx else { return }
            let controller = Unmanaged<MPVController>.fromOpaque(ctx).takeUnretainedValue()
            controller.readEvents()
        }, Unmanaged.passUnretained(self).toOpaque())
        return true
    }

    func destroy() {
        guard let mpv = handle else { return }
        handle = nil
        mpv_set_wakeup_callback(mpv, nil, nil)
        queue.async { mpv_terminate_destroy(mpv) }
    }

    // MARK: Playback

    func load(_ options: MPVLoadOptions) {
        if !options.headers.isEmpty {
            // mpv list option: "Key: Value,Key2: Value2" (commas inside values must be escaped).
            let fields = options.headers.map { key, value in
                "\(key): \(value.replacingOccurrences(of: ",", with: "\\,"))"
            }.joined(separator: ",")
            set(property: "http-header-fields", fields)
        } else {
            set(property: "http-header-fields", "")
        }
        if !options.title.isEmpty { set(property: "force-media-title", options.title) }
        didStartPlayback = false
        queue.async { [weak self] in
            self?.waitingForFile = true
            self?.updateBuffering()
        }
        var fileOptions: [String] = []
        if options.startSeconds > 1 { fileOptions.append("start=\(Int(options.startSeconds))") }
        // mpv ≥ 0.38: loadfile <url> <flags> <index> <options>
        command("loadfile", [options.url.absoluteString, "replace", "-1", fileOptions.joined(separator: ",")])
    }

    func play() { set(flag: "pause", false) }
    func pause() { set(flag: "pause", true) }
    func togglePause() { set(flag: "pause", !flag("pause")) }

    func seek(to seconds: Double) {
        command("seek", [String(format: "%.3f", max(0, seconds)), "absolute+keyframes"])
    }

    func seek(by seconds: Double) {
        command("seek", [String(format: "%.3f", seconds), "relative+keyframes"])
    }

    func setSpeed(_ speed: Double) { set(property: "speed", String(format: "%.2f", speed)) }

    func selectAudio(_ id: Int) { set(property: "aid", String(id)) }

    func selectSubtitle(_ id: Int?) { set(property: "sid", id.map(String.init) ?? "no") }

    /// Loads an external (add-on) subtitle without selecting it.
    func addSubtitle(url: String, title: String, lang: String, select: Bool = false) {
        command("sub-add", [url, select ? "select" : "auto", title, lang])
    }

    func setSubtitleDelay(_ seconds: Double) { set(property: "sub-delay", String(format: "%.3f", seconds)) }
    func setAudioDelay(_ seconds: Double) { set(property: "audio-delay", String(format: "%.3f", seconds)) }
    func setSubtitleScale(_ scale: Double) { set(property: "sub-scale", String(format: "%.2f", scale)) }
    func setSubtitlePosition(_ pos: Int) { set(property: "sub-pos", String(pos)) }

    func setVideoEnabled(_ enabled: Bool) { set(property: "vid", enabled ? "auto" : "no") }

    var position: Double { double("time-pos") }
    var duration: Double { double("duration") }

    // MARK: Tracks

    func readTracks() -> [MPVTrack] {
        let count = Int(int64("track-list/count"))
        guard count > 0 else { return [] }
        return (0..<count).compactMap { i in
            let p = "track-list/\(i)"
            guard let type = string("\(p)/type") else { return nil }
            let id = Int(int64("\(p)/id"))
            let channels = Int(int64("\(p)/demux-channel-count"))
            return MPVTrack(
                id: id,
                type: type,
                title: string("\(p)/title"),
                lang: string("\(p)/lang"),
                codec: string("\(p)/codec"),
                isSelected: flag("\(p)/selected"),
                isExternal: flag("\(p)/external"),
                isForced: flag("\(p)/forced"),
                isDefault: flag("\(p)/default"),
                channels: channels > 0 ? channels : nil
            )
        }
    }

    // MARK: Events

    private func readEvents() {
        queue.async { [weak self] in
            guard let self, let mpv = self.handle else { return }
            var tracksChanged = false
            var videoParamsChanged = false
            let wasBuffering = self.snapshot.isBuffering
            let wasPaused = self.snapshot.isPaused
            var reachedEnd = false
            var failure: String?
            while true {
                guard let event = mpv_wait_event(mpv, 0) else { break }
                let id = event.pointee.event_id
                if id == MPV_EVENT_NONE { break }
                switch id {
                case MPV_EVENT_PROPERTY_CHANGE:
                    guard let raw = event.pointee.data else { break }
                    let property = raw.assumingMemoryBound(to: mpv_event_property.self).pointee
                    let name = String(cString: property.name)
                    switch name {
                    case "time-pos": self.snapshot.position = Self.doubleValue(property) ?? self.snapshot.position
                    case "duration": self.snapshot.duration = Self.doubleValue(property) ?? 0
                    case "pause": self.snapshot.isPaused = Self.flagValue(property) ?? false
                    case "paused-for-cache":
                        self.pausedForCache = Self.flagValue(property) ?? false
                        self.updateBuffering()
                    case "seeking":
                        self.seeking = Self.flagValue(property) ?? false
                        self.updateBuffering()
                    case "core-idle":
                        let idle = Self.flagValue(property) ?? true
                        if !idle {
                            self.didStartPlayback = true
                            self.waitingForFile = false
                            self.updateBuffering()
                        }
                    case "demuxer-cache-duration": self.snapshot.cacheSeconds = Self.doubleValue(property) ?? 0
                    case "speed": self.snapshot.speed = Self.doubleValue(property) ?? 1
                    case "eof-reached":
                        if Self.flagValue(property) == true, self.didStartPlayback { reachedEnd = true }
                    case "track-list/count", "aid", "sid": tracksChanged = true
                    case "video-params/gamma", "container-fps": videoParamsChanged = true
                    default: break
                    }
                case MPV_EVENT_END_FILE:
                    if let raw = event.pointee.data {
                        let end = raw.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                        self.waitingForFile = false
                        self.updateBuffering()
                        if end.reason == MPV_END_FILE_REASON_ERROR {
                            failure = String(cString: mpv_error_string(end.error))
                        } else if end.reason == MPV_END_FILE_REASON_EOF {
                            reachedEnd = true
                        }
                    }
                case MPV_EVENT_START_FILE:
                    self.waitingForFile = true
                    self.updateBuffering()
                case MPV_EVENT_FILE_LOADED:
                    tracksChanged = true
                    videoParamsChanged = true
                case MPV_EVENT_PLAYBACK_RESTART:
                    // First frame after loading or seeking is out.
                    self.waitingForFile = false
                    self.seeking = false
                    self.didStartPlayback = true
                    self.updateBuffering()
                case MPV_EVENT_LOG_MESSAGE:
#if DEBUG
                    if let raw = event.pointee.data {
                        let msg = raw.assumingMemoryBound(to: mpv_event_log_message.self).pointee
                        print("[mpv] \(String(cString: msg.prefix)): \(String(cString: msg.text))", terminator: "")
                    }
#endif
                case MPV_EVENT_SHUTDOWN:
                    return
                default:
                    break
                }
            }

            let snapshot = self.snapshot
            let now = Date()
            // Position updates are throttled; state changes (buffering, pause) go out immediately.
            let stateChanged = snapshot.isBuffering != wasBuffering || snapshot.isPaused != wasPaused
            let publishSnapshot = stateChanged || now.timeIntervalSince(self.lastPublish) > 0.25
            if publishSnapshot { self.lastPublish = now }
            let tracks = tracksChanged ? self.readTracks() : nil
            let fps = videoParamsChanged ? self.double("container-fps") : 0
            let gamma = videoParamsChanged ? self.string("video-params/gamma") : nil
            let primaries = videoParamsChanged ? self.string("video-params/primaries") : nil

            DispatchQueue.main.async { [weak self] in
                guard let self, let delegate = self.delegate else { return }
                MainActor.assumeIsolated {
                    if publishSnapshot || reachedEnd { delegate.mpvDidUpdate(snapshot) }
                    if let tracks { delegate.mpvTracksChanged(tracks) }
                    if videoParamsChanged { delegate.mpvVideoParamsChanged(fps: fps, gamma: gamma, primaries: primaries) }
                    if let failure { delegate.mpvDidFail(failure) }
                    if reachedEnd { delegate.mpvDidReachEnd() }
                }
            }
        }
    }

    private static func doubleValue(_ p: mpv_event_property) -> Double? {
        guard p.format == MPV_FORMAT_DOUBLE, let data = p.data else { return nil }
        return data.assumingMemoryBound(to: Double.self).pointee
    }

    private static func flagValue(_ p: mpv_event_property) -> Bool? {
        guard p.format == MPV_FORMAT_FLAG, let data = p.data else { return nil }
        return data.assumingMemoryBound(to: Int32.self).pointee != 0
    }

    // MARK: Raw property helpers

    private func set(option name: String, _ value: String) {
        guard let handle else { return }
        mpv_set_option_string(handle, name, value)
    }

    private func set(property name: String, _ value: String) {
        guard let handle else { return }
        mpv_set_property_string(handle, name, value)
    }

    private func set(flag name: String, _ value: Bool) {
        guard let handle else { return }
        var data: Int32 = value ? 1 : 0
        mpv_set_property(handle, name, MPV_FORMAT_FLAG, &data)
    }

    private func flag(_ name: String) -> Bool {
        guard let handle else { return false }
        var data: Int32 = 0
        mpv_get_property(handle, name, MPV_FORMAT_FLAG, &data)
        return data != 0
    }

    private func double(_ name: String) -> Double {
        guard let handle else { return 0 }
        var data = 0.0
        mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, &data)
        return data
    }

    private func int64(_ name: String) -> Int64 {
        guard let handle else { return 0 }
        var data: Int64 = 0
        mpv_get_property(handle, name, MPV_FORMAT_INT64, &data)
        return data
    }

    private func string(_ name: String) -> String? {
        guard let handle, let cString = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(cString) }
        let value = String(cString: cString)
        return value.isEmpty ? nil : value
    }

    private func command(_ name: String, _ args: [String]) {
        guard let handle else { return }
        var cStrings: [UnsafeMutablePointer<CChar>?] = ([name] + args).map { strdup($0) }
        cStrings.append(nil)
        defer { cStrings.forEach { free($0) } }
        cStrings.withUnsafeMutableBufferPointer { buffer in
            buffer.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buffer.count) { argv in
                _ = mpv_command(handle, argv)
            }
        }
    }
}

/// MoltenVK can set drawableSize to 1×1 to force-complete a presentation; ignore that
/// (mpv-player/mpv#13651, same workaround as MPVKit's demo).
final class MPVMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                super.drawableSize = newValue
            }
        }
    }
}
