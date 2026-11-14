import SwiftUI
import UIKit
import NuvioShared

/// Artwork loaded through [ImageLoader]. Unlike AsyncImage it keeps a memory cache, so cards
/// recycled by lazy rows don't come back blank, and retries a load cancelled mid-flight.
struct RemoteImage: View {
    let url: String?
    var contentMode: ContentMode = .fill
    @State private var image: UIImage?
    @State private var loadedUrl: String?

    var body: some View {
        ZStack {
            Rectangle().fill(Color.white.opacity(0.08))
            if let image, loadedUrl == url {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            }
        }
        .task(id: url) {
            guard let url else { image = nil; loadedUrl = nil; return }
            if let cached = ImageLoader.shared.cached(url) {
                image = cached; loadedUrl = url
                return
            }
            if let loaded = await ImageLoader.shared.load(url), !Task.isCancelled {
                image = loaded; loadedUrl = url
            }
        }
    }
}

/// Shared poster/backdrop loader: in-memory + on-disk cache, a browser-like User-Agent (some
/// add-on image hosts reject the default one) and tolerant URL parsing.
final class ImageLoader: @unchecked Sendable {
    static let shared = ImageLoader()

    private let memory = NSCache<NSString, UIImage>()
    private let session: URLSession

    private init() {
        memory.totalCostLimit = 200 * 1024 * 1024
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 300 * 1024 * 1024)
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 30
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (AppleTV; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Accept": "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5",
        ]
        session = URLSession(configuration: config)
    }

    func cached(_ url: String) -> UIImage? { memory.object(forKey: url as NSString) }

    func load(_ raw: String) async -> UIImage? {
        guard let url = Self.parse(raw) else { return nil }
        for attempt in 0..<2 {
            do {
                let (data, response) = try await session.data(from: url)
                if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return nil }
                guard let decoded = UIImage(data: data) else { return nil }
                let image = await decoded.byPreparingForDisplay() ?? decoded
                memory.setObject(image, forKey: raw as NSString, cost: data.count)
                return image
            } catch {
                // A recycled card cancels its task; only retry genuine network hiccups.
                if Task.isCancelled || attempt == 1 { return nil }
                try? await Task.sleep(nanoseconds: 400_000_000)
            }
        }
        return nil
    }

    /// Accepts protocol-relative ("//host/x.jpg") and unencoded URLs (spaces, "|", unicode).
    static func parse(_ raw: String) -> URL? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if s.hasPrefix("//") { s = "https:" + s }
        if let url = URL(string: s), url.scheme != nil, url.host != nil { return url }
        if let encoded = s.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed.union(CharacterSet(charactersIn: "#%"))),
           let url = URL(string: encoded), url.scheme != nil { return url }
        return nil
    }
}

/// Artwork that always covers the whole screen, edge to edge (safe areas included), cropped
/// to fill. Used behind details, Modern home and the Classic focus backdrop.
struct FullScreenBackdrop<Overlay: View>: View {
    let url: String?
    var opacity: Double = 1
    @ViewBuilder var overlay: () -> Overlay

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                if let url {
                    RemoteImage(url: url)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                        .opacity(opacity)
                        .id(url)
                        .transition(.opacity)
                }
                overlay()
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.5), value: url)
    }
}

extension FullScreenBackdrop where Overlay == EmptyView {
    init(url: String?, opacity: Double = 1) {
        self.init(url: url, opacity: opacity) { EmptyView() }
    }
}

/// White border drawn inside a card while it has focus (tvOS card style lifts it with the card).
struct FocusBorder: View {
    @Environment(\.isFocused) private var isFocused
    var cornerRadius: CGFloat = 12
    var lineWidth: CGFloat = 4

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .strokeBorder(Color.white, lineWidth: lineWidth)
            .opacity(isFocused ? 1 : 0)
            .animation(.easeOut(duration: 0.12), value: isFocused)
            .allowsHitTesting(false)
    }
}

extension View {
    /// Adds the white focus border; apply inside a `.card` button's label.
    func focusBorder(cornerRadius: CGFloat = 12) -> some View {
        overlay(FocusBorder(cornerRadius: cornerRadius))
    }
}

/// Calls [onFocus] when the enclosing focusable (card button) gains focus.
struct FocusReporter: View {
    @Environment(\.isFocused) private var isFocused
    let onFocus: () -> Void

    var body: some View {
        Color.clear
            .onChange(of: isFocused) { _, focused in
                if focused { onFocus() }
            }
    }
}

/// Long-press (press and hold) menu for catalog / library cards.
struct ItemContextMenu: View {
    let app: AppModel
    let item: MetaPreview

    var body: some View {
        let inLibrary = app.isInLibrary(item.id)
        Button {
            app.core.toggleLibraryPreview(preview: item)
        } label: {
            Label(inLibrary ? "Remove from Library" : "Add to Library", systemImage: inLibrary ? "bookmark.slash" : "bookmark")
        }
        if item.apiType == "movie" {
            let watched = app.core.isPreviewWatched(preview: item)
            Button {
                app.core.setPreviewWatched(preview: item, isWatched: !watched)
            } label: {
                Label(watched ? "Mark as unwatched" : "Mark as watched", systemImage: watched ? "eye.slash" : "eye")
            }
        }
        if app.continueWatching.contains(where: { $0.contentId == item.id }) {
            Button(role: .destructive) {
                app.core.removeProgress(contentId: item.id)
            } label: {
                Label("Remove from Continue Watching", systemImage: "xmark.circle")
            }
        }
    }
}

/// Poster card used in every row and grid. Navigates to the detail screen.
struct PosterCard: View {
    @EnvironmentObject private var app: AppModel
    let item: MetaPreview
    var width: CGFloat = 220
    var onFocus: ((MetaPreview) -> Void)? = nil

    private var height: CGFloat {
        let ratio = CGFloat(item.posterShape.aspectRatio())
        return width / max(ratio, 0.1)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            NavigationLink(value: Route.detail(item)) {
                ZStack {
                    RemoteImage(url: item.poster ?? item.backdropUrl)
                    if item.poster == nil && item.backdropUrl == nil {
                        Text(item.name)
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .padding()
                    }
                }
                .frame(width: width, height: height)
                .clipped()
                .focusBorder()
                .background(FocusReporter { onFocus?(item) })
            }
            .buttonStyle(.card)
            .contextMenu { ItemContextMenu(app: app, item: item) }

            Text(item.name)
                .font(.caption)
                .lineLimit(1)
                .frame(width: width, alignment: .leading)
                .foregroundStyle(.secondary)
        }
    }
}

extension MetaPreview {
    /// Artwork for 16:9 cards, in Android ContentCard's order: the add-on's landscape poster,
    /// then its background, then its poster.
    var landscapeArtwork: String? { landscapePoster ?? background ?? poster }
}

/// 16:9 card with the title on it (Modern layout rows with landscape posters on).
struct LandscapeCard: View {
    @EnvironmentObject private var app: AppModel
    let item: MetaPreview
    var width: CGFloat = 420
    var onFocus: ((MetaPreview) -> Void)? = nil

    var body: some View {
        NavigationLink(value: Route.detail(item)) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: item.landscapeArtwork)
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom)
                if let logo = item.logo {
                    RemoteImage(url: logo, contentMode: .fit)
                        .frame(maxWidth: width * 0.55, maxHeight: 70, alignment: .bottomLeading)
                        .padding(16)
                } else {
                    Text(item.name).font(.headline).lineLimit(2).padding(16)
                }
            }
            .frame(width: width, height: width * 9 / 16)
            .clipped()
            .focusBorder()
            .background(FocusReporter { onFocus?(item) })
        }
        .buttonStyle(.card)
        .contextMenu { ItemContextMenu(app: app, item: item) }
    }
}

/// Horizontal row of poster (or landscape) cards with a header.
struct MetaRowView: View {
    let title: String
    let items: [MetaPreview]
    var seeAll: Route? = nil
    var landscape = false
    var onFocus: ((MetaPreview) -> Void)? = nil
    var onReachEnd: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(title).font(.headline)
                Spacer()
                if let seeAll {
                    NavigationLink("See all", value: seeAll)
                        .font(.caption)
                }
            }
            .padding(.horizontal, 60)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 40) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        Group {
                            if landscape {
                                LandscapeCard(item: item, onFocus: onFocus)
                            } else {
                                PosterCard(item: item, onFocus: onFocus)
                            }
                        }
                        .onAppear {
                            if index == items.count - 1 { onReachEnd?() }
                        }
                    }
                }
                .padding(.horizontal, 60)
                .padding(.vertical, 30)
            }
        }
        .focusSection()
    }
}

struct LoadingView: View {
    var label: String = "Loading…"
    var body: some View {
        VStack(spacing: 20) {
            ProgressView()
            Text(label).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct MessageView: View {
    let title: String
    var message: String? = nil
    var systemImage: String = "exclamationmark.triangle"

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: systemImage).font(.largeTitle)
            Text(title).font(.headline)
            if let message {
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 1000)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(80)
    }
}

extension Meta {
    /// "2021 · 2h 10m · ★ 7.4 · Drama, Thriller"
    var infoLine: String {
        var parts: [String] = []
        if let releaseInfo, !releaseInfo.isEmpty { parts.append(releaseInfo) }
        if let runtime, !runtime.isEmpty { parts.append(runtime) }
        if let ageRating, !ageRating.isEmpty { parts.append(ageRating) }
        if let imdbRating { parts.append("★ \(imdbRating)") }
        if !genres.isEmpty { parts.append(genres.prefix(3).joined(separator: ", ")) }
        return parts.joined(separator: "  ·  ")
    }
}
