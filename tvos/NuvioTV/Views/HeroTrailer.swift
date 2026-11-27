import Combine
import SwiftUI
import UIKit
import NuvioShared

extension Notification.Name {
    /// Posted when a player opens: any hero trailer stops at once.
    static let nuvioPlaybackWillStart = Notification.Name("nuvio.playbackWillStart")
}

/// The hero title's trailer, silent, over its artwork (Settings > Home > Hero trailers). The link
/// is the one the Details page plays (looked up once per title and shared both ways), played by
/// mpv like on the Details page. It starts after [delay] seconds on the same title and only
/// appears once frames are really playing, so a title without a trailer, or a link that doesn't
/// play, just keeps its artwork.
struct HeroTrailer: View {
    @EnvironmentObject private var app: AppModel
    /// nil stops it (no title, or the hero isn't on screen).
    let item: MetaPreview?
    var delay: Double = 1.5
    /// The trailer played to the end.
    var onFinished: (() -> Void)? = nil
    /// True while the trailer is showing.
    var onShowingChange: ((Bool) -> Void)? = nil
    @StateObject private var model = HeroTrailerModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let enabled = app.settings.heroTrailers && app.core.hasTrailerApi
        ZStack {
            Color.clear
            if let controller = model.controller {
                MPVVideoView(controller: controller)
                    // A new mpv for every trailer: its view is made afresh with it.
                    .id(ObjectIdentifier(controller))
            }
        }
        .opacity(model.isShowing ? 1 : 0)
        .animation(.easeInOut(duration: 0.6), value: model.isShowing)
        .allowsHitTesting(false)
        .task(id: "\(enabled ? item?.id ?? "" : "")") {
            await model.run(item: enabled ? item : nil, core: app.core, delay: delay)
        }
        .onDisappear { model.stop() }
        .onReceive(NotificationCenter.default.publisher(for: .nuvioPlaybackWillStart)) { _ in model.stop() }
        .onReceive(model.finished) { onFinished?() }
        .onChange(of: model.isShowing) { _, showing in onShowingChange?(showing) }
        // Back from the Home screen / screensaver: carry on where it paused.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active && model.isShowing { model.resume() }
        }
    }
}

/// Plays one hero trailer at a time through its own mpv instance (silent, no subtitles, SDR).
@MainActor
final class HeroTrailerModel: ObservableObject, MPVControllerDelegate {
    /// The current trailer's mpv; a fresh one each time, destroyed when it stops.
    @Published private(set) var controller: MPVController?
    @Published private(set) var isShowing = false
    let finished = PassthroughSubject<Void, Never>()
    private var link: String?
    private var core: NuvioCore?
    private var stalledSince: Date?

    func run(item: MetaPreview?, core: NuvioCore, delay: Double) async {
        stop()
        guard let item else { return }
        try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        guard !Task.isCancelled else { return }
        guard let link = (try? await core.heroTrailerUrl(preview: item)) ?? nil,
              !Task.isCancelled, let url = URL(string: link) else { return }
        let controller = MPVController()
        controller.delegate = self
        self.core = core
        self.link = link
        // Its picture surface goes on screen first (still hidden), as the player screen does.
        self.controller = controller
        try? await Task.sleep(nanoseconds: 150_000_000)
        guard !Task.isCancelled, self.controller === controller else { return }
        var options = MPVSetupOptions()
        options.silent = true
        options.subtitlesEnabled = false
        options.hdrPassthrough = false
        options.cacheSeconds = 30
        guard controller.setup(options) else {
            stop()
            return
        }
        controller.load(MPVLoadOptions(url: url))
        controller.play()
        // Shown by mpvDidUpdate once frames play; a link that hasn't started in 15 s is dropped.
        try? await Task.sleep(nanoseconds: 15_000_000_000)
        guard !Task.isCancelled, self.controller === controller, !isShowing else { return }
        forgetLink()
        stop()
    }

    func resume() { controller?.play() }

    func stop() {
        isShowing = false
        stalledSince = nil
        if let controller {
            controller.delegate = nil
            controller.pause()
            controller.destroy()
        }
        controller = nil
        link = nil
    }

    /// A link that failed is looked up afresh next time (Details shares it).
    private func forgetLink() {
        if let link, let core { core.forgetTrailerUrl(url: link) }
    }

    // MARK: MPVControllerDelegate

    func mpvDidUpdate(_ snapshot: MPVController.Snapshot) {
        guard controller != nil else { return }
        if !snapshot.isBuffering && !snapshot.isPaused && snapshot.position > 0.3 {
            stalledSince = nil
            if !isShowing { isShowing = true }
        } else if isShowing && snapshot.isBuffering {
            // Stopped delivering for 4 s: back to the artwork.
            if let since = stalledSince {
                if Date().timeIntervalSince(since) > 4 { stop() }
            } else {
                stalledSince = Date()
            }
        }
    }

    func mpvTracksChanged(_ tracks: [MPVTrack]) {}

    func mpvVideoParamsChanged(fps: Double, gamma: String?, primaries: String?, frameReady: Bool) {}

    func mpvDidReachEnd() {
        guard controller != nil else { return }
        stop()
        finished.send()
    }

    func mpvDidFail(_ message: String) {
        guard controller != nil else { return }
        forgetLink()
        stop()
    }
}

/// Reports whether this view is (mostly) on screen, so the hero trailer stops when the hero is
/// scrolled away.
struct OnScreenReporter: View {
    let onChange: (Bool) -> Void

    var body: some View {
        GeometryReader { geo in
            let frame = geo.frame(in: .global)
            let screen = UIScreen.main.bounds
            let visible = frame.maxY > screen.minY + frame.height * 0.4 && frame.minY < screen.maxY - frame.height * 0.4
            Color.clear
                .onAppear { onChange(visible) }
                .onChange(of: visible) { _, now in onChange(now) }
        }
    }
}
