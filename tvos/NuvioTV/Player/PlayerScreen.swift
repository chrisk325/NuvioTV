import AVKit
import SwiftUI
import NuvioShared

struct PlaybackRequest: Identifiable {
    let id = UUID()
    let url: String
    let headers: [String: String]
    let meta: Meta
    let video: Video?
    let videoId: String
    let title: String
    let startPositionMs: Int64
}

struct PlayerScreen: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    let request: PlaybackRequest

    @StateObject private var model = PlayerModel()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PlayerViewController(player: model.player)
                .ignoresSafeArea()
            if let message = model.errorMessage {
                VStack(spacing: 24) {
                    MessageView(
                        title: "Playback failed",
                        message: message + "\n\nApple TV's built-in player supports MP4/MOV/HLS. MKV and some codecs need a different stream."
                    )
                    Button("Back") { dismiss() }
                }
                .background(Color.black.opacity(0.85))
            }
        }
        .onAppear { model.start(request, core: app.core) }
        .onDisappear { model.stop() }
    }
}

/// Owns the AVPlayer, resumes from saved progress and reports progress back to the shared core.
@MainActor
final class PlayerModel: ObservableObject {
    let player = AVPlayer()
    @Published var errorMessage: String?

    private var request: PlaybackRequest?
    private var core: NuvioCore?
    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?
    private var finished = false

    func start(_ request: PlaybackRequest, core: NuvioCore) {
        guard self.request == nil else { return }
        self.request = request
        self.core = core

        guard let url = URL(string: request.url) else {
            errorMessage = "Invalid stream URL."
            return
        }
        var options: [String: Any] = [:]
        if !request.headers.isEmpty {
            // Widely used AVURLAsset option for per-request HTTP headers (proxyHeaders.request).
            options["AVURLAssetHTTPHeaderFieldsKey"] = request.headers
        }
        let item = AVPlayerItem(asset: AVURLAsset(url: url, options: options))
        item.externalMetadata = [metadataItem(.commonIdentifierTitle, request.title)]

        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let failed = item.status == .failed
            let message = item.error?.localizedDescription
            guard failed else { return }
            Task { @MainActor in
                self?.errorMessage = message ?? "The stream couldn't be opened."
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.finished = true
                self?.saveProgress()
            }
        }

        player.replaceCurrentItem(with: item)
        if request.startPositionMs > 0 {
            player.seek(to: CMTime(value: request.startPositionMs, timescale: 1000))
        }
        player.play()

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 10, preferredTimescale: 1),
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.saveProgress() }
        }
    }

    func stop() {
        saveProgress()
        player.pause()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        timeObserver = nil
        endObserver = nil
        statusObservation?.invalidate()
        statusObservation = nil
        player.replaceCurrentItem(with: nil)
    }

    private func saveProgress() {
        guard let request, let core, let item = player.currentItem else { return }
        let duration = item.duration.seconds
        guard duration.isFinite, duration > 0 else { return }
        let position = finished ? duration : player.currentTime().seconds
        guard position.isFinite, position > 0 else { return }
        core.saveProgress(
            meta: request.meta,
            videoId: request.videoId,
            season: request.video?.season,
            episode: request.video?.episode,
            episodeTitle: request.video?.title,
            positionMs: Int64(position * 1000),
            durationMs: Int64(duration * 1000)
        )
    }

    private func metadataItem(_ identifier: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
        let item = AVMutableMetadataItem()
        item.identifier = identifier
        item.value = value as NSString
        item.extendedLanguageTag = "und"
        return item.copy() as! AVMetadataItem
    }
}

struct PlayerViewController: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player { controller.player = player }
    }
}
