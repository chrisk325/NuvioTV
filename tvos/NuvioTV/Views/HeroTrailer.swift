import AVFoundation
import Combine
import SwiftUI
import UIKit
import NuvioShared

extension Notification.Name {
    /// Posted when a player opens: any hero trailer stops at once.
    static let nuvioPlaybackWillStart = Notification.Name("nuvio.playbackWillStart")
}

/// The hero title's trailer, silent, over its artwork (Settings > Home > Hero trailers). The link
/// is the one the Details page plays (looked up once per title and shared both ways). It starts
/// after [delay] seconds on the same title and only appears once frames are really playing, so a
/// title without a trailer, or a link the system player can't open, just keeps its artwork.
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
        PlayerLayerView(player: model.player)
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
                if phase == .active && model.isShowing { model.player.play() }
            }
    }
}

@MainActor
final class HeroTrailerModel: ObservableObject {
    /// A fresh player for every trailer: an AVPlayer that has failed once can't play anything
    /// again, which left the hero without trailers after the first one went wrong.
    @Published private(set) var player = HeroTrailerModel.makePlayer()
    @Published private(set) var isShowing = false
    let finished = PassthroughSubject<Void, Never>()
    private var endObserver: NSObjectProtocol?

    private static func makePlayer() -> AVPlayer {
        let player = AVPlayer()
        player.isMuted = true
        player.allowsExternalPlayback = false
        player.preventsDisplaySleepDuringVideoPlayback = false
        player.actionAtItemEnd = .pause
        return player
    }

    func run(item: MetaPreview?, core: NuvioCore, delay: Double) async {
        stop()
        guard let item else { return }
        try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        guard !Task.isCancelled else { return }
        guard let link = (try? await core.heroTrailerUrl(preview: item)) ?? nil,
              !Task.isCancelled, let url = URL(string: link) else { return }
        let player = Self.makePlayer()
        let playerItem = AVPlayerItem(url: url)
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: playerItem, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.stop()
                self.finished.send()
            }
        }
        player.replaceCurrentItem(with: playerItem)
        self.player = player
        player.play()

        // Shown once it really plays (checked for up to 15 s); otherwise the artwork stays.
        var started = false
        for _ in 0..<150 {
            if Task.isCancelled || self.player !== player { return }
            if playerItem.status == .failed || player.status == .failed { break }
            if player.timeControlStatus == .playing && playerItem.currentTime().seconds > 0.3 {
                started = true
                break
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        guard started else {
            // The link didn't play (expired or refused): looked up afresh next time.
            if self.player === player {
                core.forgetTrailerUrl(url: link)
                stop()
            }
            return
        }
        isShowing = true

        // While it shows: a link that fails, or a stream that stops delivering for 4 s, fades
        // back to the artwork (a failed link is looked up afresh next time).
        var stalledChecks = 0
        while isShowing && self.player === player {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if Task.isCancelled || self.player !== player || !isShowing { return }
            if playerItem.status == .failed || player.status == .failed {
                core.forgetTrailerUrl(url: link)
                stop()
                return
            }
            stalledChecks = player.timeControlStatus == .playing ? 0 : stalledChecks + 1
            if stalledChecks >= 8 {
                stop()
                return
            }
        }
    }

    func stop() {
        isShowing = false
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
    }
}

/// An AVPlayer's picture, filling its frame.
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    final class LayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.playerLayer.videoGravity = .resizeAspectFill
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {
        if view.playerLayer.player !== player { view.playerLayer.player = player }
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
