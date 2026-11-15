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
    func url(for stream: String) -> URL? {
        guard let encoded = stream.addingPercentEncoding(withAllowedCharacters: Self.queryValueAllowed) else { return nil }
        switch self {
        case .infuse: return URL(string: "infuse://x-callback-url/play?url=\(encoded)")
        case .vlc: return URL(string: "vlc-x-callback://x-callback-url/stream?url=\(encoded)")
        }
    }

    /// Unreserved characters only, so the stream URL's own ?, & and = survive as one parameter.
    private static let queryValueAllowed: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~")
        return set
    }()
}

/// Hands the stream to the external app and closes; explains when the app isn't installed.
struct ExternalPlayerScreen: View {
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
            guard let url = player.url(for: request.url) else { failed = true; return }
            let opened = await UIApplication.shared.open(url)
            if opened { dismiss() } else { failed = true }
        }
    }
}
