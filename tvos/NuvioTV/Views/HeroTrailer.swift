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
    let player = AVPlayer()
    @Published private(set) var isShowing = false
    let finished = PassthroughSubject<Void, Never>()
    private var endObserver: NSObjectProtocol?

    init() {
        player.isMuted = true
        player.allowsExternalPlayback = false
        player.preventsDisplaySleepDuringVideoPlayback = false
        player.actionAtItemEnd = .pause
    }

    func run(item: MetaPreview?, core: NuvioCore, delay: Double) async {
        stop()
        guard let item else { return }
        try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        guard !Task.isCancelled else { return }
        guard let link = (try? await core.heroTrailerUrl(preview: item)) ?? nil,
              !Task.isCancelled, let url = URL(string: link) else { return }
        let playerItem = AVPlayerItem(url: url)
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: playerItem, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.isShowing = false
                self.finished.send()
            }
        }
        player.replaceCurrentItem(with: playerItem)
        player.play()
        // Shown once it really plays (checked for up to 15 s); otherwise the artwork stays.
        for _ in 0..<150 {
            if Task.isCancelled || player.currentItem !== playerItem { return }
            if playerItem.status == .failed { break }
            if player.timeControlStatus == .playing && playerItem.currentTime().seconds > 0.3 {
                isShowing = true
                return
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        if player.currentItem === playerItem { stop() }
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
