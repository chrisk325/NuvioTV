import SwiftUI
import UIKit
import CoreImage
import ImageIO
import NuvioShared

/// Artwork loaded through [ImageLoader]. Unlike AsyncImage it keeps a memory cache, so cards
/// recycled by lazy rows don't come back blank, and retries a load cancelled mid-flight.
/// Images are decoded off the main thread at no more than [maxPixel] on their longest side,
/// so a 3000 px add-on poster doesn't cost a full-size bitmap per card while scrolling.
struct RemoteImage: View {
    let url: String?
    var contentMode: ContentMode = .fill
    var maxPixel: CGFloat = ImageLoader.fullSize
    @State private var image: UIImage?
    @State private var loadedUrl: String?

    var body: some View {
        ZStack {
            if let image, loadedUrl == url {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else if contentMode == .fill {
                // Placeholder only while artwork loads, and never behind logos (.fit), whose
                // transparent areas would otherwise show it as a grey box.
                Rectangle().fill(Color.white.opacity(0.08))
            }
        }
        .task(id: url) {
            guard let url else { image = nil; loadedUrl = nil; return }
            if let cached = ImageLoader.shared.cached(url, maxPixel: maxPixel) {
                image = cached; loadedUrl = url
                return
            }
            if let loaded = await ImageLoader.shared.load(url, maxPixel: maxPixel), !Task.isCancelled {
                image = loaded; loadedUrl = url
            }
        }
    }
}

/// Shared poster/backdrop loader: in-memory + on-disk cache, a browser-like User-Agent (some
/// add-on image hosts reject the default one) and tolerant URL parsing. Decodes with ImageIO
/// straight to the needed size on a background thread, and shares in-flight downloads.
final class ImageLoader: @unchecked Sendable {
    static let shared = ImageLoader()
    /// Cap for full-screen artwork (backdrops, heroes).
    static let fullSize: CGFloat = 1920
    /// Portrait posters / cards.
    static let cardSize: CGFloat = 720
    /// Landscape cards and episode thumbnails.
    static let wideCardSize: CGFloat = 960

    private let memory = NSCache<NSString, UIImage>()
    private let session: URLSession
    private let lock = NSLock()
    private var inFlight: [String: Task<Data?, Never>] = [:]
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])

    private init() {
        memory.totalCostLimit = 300 * 1024 * 1024
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 300 * 1024 * 1024)
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 30
        config.httpMaximumConnectionsPerHost = 6
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (AppleTV; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Accept": "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5",
        ]
        session = URLSession(configuration: config)
    }

    private func key(_ url: String, _ maxPixel: CGFloat) -> NSString { NSString(string: "\(Int(maxPixel))|\(url)") }

    func cached(_ url: String, maxPixel: CGFloat = ImageLoader.fullSize) -> UIImage? {
        memory.object(forKey: key(url, maxPixel))
    }

    func load(_ raw: String, maxPixel: CGFloat = ImageLoader.fullSize) async -> UIImage? {
        let cacheKey = key(raw, maxPixel)
        if let hit = memory.object(forKey: cacheKey) { return hit }
        guard let data = await data(raw), !Task.isCancelled else { return nil }
        guard let image = Self.decode(data, maxPixel: maxPixel) else { return nil }
        memory.setObject(image, forKey: cacheKey, cost: Self.cost(image))
        return image
    }

    /// A small, pre-blurred copy of the artwork for full-screen ambient backdrops: blurring once
    /// here is far cheaper than a live full-screen blur re-rendered on every focus change.
    func blurred(_ raw: String, radius: Double = 10) async -> UIImage? {
        let cacheKey = NSString(string: "blur\(Int(radius))|\(raw)")
        if let hit = memory.object(forKey: cacheKey) { return hit }
        guard let data = await data(raw), !Task.isCancelled,
              let small = Self.decode(data, maxPixel: 640)?.cgImage else { return nil }
        let input = CIImage(cgImage: small)
        guard let filter = CIFilter(name: "CIGaussianBlur") else { return nil }
        filter.setValue(input.clampedToExtent(), forKey: kCIInputImageKey)
        filter.setValue(radius, forKey: kCIInputRadiusKey)
        guard let output = filter.outputImage?.cropped(to: input.extent),
              let cg = ciContext.createCGImage(output, from: input.extent) else { return nil }
        let image = UIImage(cgImage: cg)
        memory.setObject(image, forKey: cacheKey, cost: Self.cost(image))
        return image
    }

    /// One download per URL at a time; later callers wait for the same request.
    private func data(_ raw: String) async -> Data? {
        guard let url = Self.parse(raw) else { return nil }
        let session = self.session
        let task: Task<Data?, Never> = lock.withLock {
            if let existing = inFlight[raw] { return existing }
            let created = Task.detached(priority: .userInitiated) { () -> Data? in
                for attempt in 0..<2 {
                    do {
                        let (data, response) = try await session.data(from: url)
                        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return nil }
                        return data
                    } catch {
                        if attempt == 1 { return nil }
                        try? await Task.sleep(nanoseconds: 400_000_000)
                    }
                }
                return nil
            }
            inFlight[raw] = created
            return created
        }
        let result = await task.value
        lock.withLock { inFlight[raw] = nil }
        return result
    }

    /// Decodes (and downsamples) to a ready-to-draw bitmap; falls back to UIImage for formats
    /// ImageIO can't thumbnail.
    private static func decode(_ data: Data, maxPixel: CGFloat) -> UIImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        if let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) {
            let options = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            ] as CFDictionary
            if let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options) {
                return UIImage(cgImage: cg)
            }
        }
        guard let image = UIImage(data: data) else { return nil }
        return image.preparingForDisplay() ?? image
    }

    private static func cost(_ image: UIImage) -> Int {
        guard let cg = image.cgImage else { return 1 }
        return cg.bytesPerRow * cg.height
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
    /// Crossfade when the artwork changes (off for the Modern home hero).
    var animated = true
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
        .animation(animated ? .easeInOut(duration: 0.5) : nil, value: url)
    }
}

extension FullScreenBackdrop where Overlay == EmptyView {
    init(url: String?, opacity: Double = 1) {
        self.init(url: url, opacity: opacity) { EmptyView() }
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
            app.toggleLibrary(preview: item)
        } label: {
            Label(inLibrary ? "Remove from Library" : "Add to Library", systemImage: inLibrary ? "bookmark.slash" : "bookmark")
        }
        let watched = app.core.isPreviewWatched(preview: item)
        if item.apiType == "movie" {
            Button {
                app.core.setPreviewWatched(preview: item, isWatched: !watched)
            } label: {
                Label(watched ? "Mark as unwatched" : "Mark as watched", systemImage: watched ? "eye.slash" : "eye")
            }
        } else {
            // Shows: loads the episode list and marks every episode (Android toggleSeriesWatched).
            Button {
                Task { @MainActor in
                    try? await app.core.setSeriesWatched(preview: item, isWatched: !watched)
                }
            } label: {
                Label(watched ? "Mark show as unwatched" : "Mark show as watched", systemImage: watched ? "eye.slash" : "eye")
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
    var onMoveUp: (() -> Void)? = nil

    private var height: CGFloat {
        let ratio = CGFloat(item.posterShape.aspectRatio())
        return width / max(ratio, 0.1)
    }

    var body: some View {
        // Landscape posters (Settings > Home) switch every poster card in the app, as on Android.
        if app.settings.modernLandscapePosters {
            LandscapeCard(item: item, width: PosterGrid.landscapeWidth, onFocus: onFocus, onMoveUp: onMoveUp)
        } else {
            portrait
        }
    }

    private var portrait: some View {
        // Room for the focused poster's lift so it doesn't touch the title.
        VStack(alignment: .leading, spacing: 28) {
            NavigationLink(value: Route.detail(item)) {
                ZStack {
                    RemoteImage(url: item.poster ?? item.backdropUrl, maxPixel: ImageLoader.cardSize)
                    if item.poster == nil && item.backdropUrl == nil {
                        Text(item.name)
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .padding()
                    }
                }
                .frame(width: width, height: height)
                .clipped()
                .overlay(alignment: .topTrailing) {
                    if app.core.isPreviewWatched(preview: item) { WatchedBadge().padding(10) }
                }
                .background(FocusReporter { onFocus?(item) })
            }
            .buttonStyle(.card)
            .onMoveUp(onMoveUp)
            .contextMenu { ItemContextMenu(app: app, item: item) }

            Text(item.name)
                .font(.caption)
                .lineLimit(1)
                .frame(width: width, alignment: .leading)
                .foregroundStyle(.secondary)
        }
    }
}

/// Grid columns and card sizes for portrait vs landscape posters.
enum PosterGrid {
    static let portraitWidth: CGFloat = 220
    static let landscapeWidth: CGFloat = 400

    static func columns(landscape: Bool) -> [GridItem] {
        landscape
            ? Array(repeating: GridItem(.fixed(landscapeWidth), spacing: 40), count: 4)
            : Array(repeating: GridItem(.fixed(portraitWidth), spacing: 40), count: 7)
    }
}

extension MetaPreview {
    /// Artwork for 16:9 cards, in Android ContentCard's order: the add-on's landscape poster,
    /// then its background, then its poster.
    var landscapeArtwork: String? { landscapePoster ?? background ?? poster }
}

/// Check mark on cards of watched movies and fully watched shows (local, Trakt, Simkl, AniList,
/// Nuvio account).
struct WatchedBadge: View {
    var body: some View {
        Image(systemName: "checkmark")
            .font(.system(size: 18, weight: .heavy))
            .foregroundStyle(.white)
            .frame(width: 36, height: 36)
            .background(Circle().fill(Color(white: 0.38).opacity(0.92)))
            .overlay(Circle().strokeBorder(Color.white.opacity(0.25), lineWidth: 1))
            .shadow(color: .black.opacity(0.4), radius: 4)
            .accessibilityLabel("Watched")
    }
}

/// 16:9 card with the title on it (Modern layout rows with landscape posters on).
struct LandscapeCard: View {
    @EnvironmentObject private var app: AppModel
    let item: MetaPreview
    var width: CGFloat = 420
    var onFocus: ((MetaPreview) -> Void)? = nil
    var onMoveUp: (() -> Void)? = nil

    var body: some View {
        NavigationLink(value: Route.detail(item)) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: item.landscapeArtwork, maxPixel: ImageLoader.wideCardSize)
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
            .overlay(alignment: .topTrailing) {
                if app.core.isPreviewWatched(preview: item) { WatchedBadge().padding(12) }
            }
            .background(FocusReporter { onFocus?(item) })
        }
        .buttonStyle(.card)
        .onMoveUp(onMoveUp)
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
    /// Up on any card in the row (details page rows jump to an anchor above).
    var onMoveUp: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // "See all" sits right after the catalog name, not at the far edge.
            HStack(alignment: .center, spacing: 24) {
                Text(title).font(.headline)
                if let seeAll {
                    NavigationLink(value: seeAll) {
                        HStack(spacing: 6) {
                            Text("See all")
                            Image(systemName: "chevron.right")
                        }
                    }
                    .buttonStyle(SeeAllButtonStyle())
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 60)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 40) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        Group {
                            if landscape {
                                LandscapeCard(item: item, onFocus: onFocus, onMoveUp: onMoveUp)
                            } else {
                                PosterCard(item: item, onFocus: onFocus, onMoveUp: onMoveUp)
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

/// Small pill for "See all" next to a row title: white when focused.
struct SeeAllButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Pill(configuration: configuration)
    }

    private struct Pill: View {
        let configuration: ButtonStyle.Configuration
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .font(.caption.weight(.semibold))
                .foregroundStyle(isFocused ? Color.black : Color.white.opacity(0.85))
                .padding(.horizontal, 18)
                .padding(.vertical, 8)
                .background(Capsule().fill(isFocused ? Color.white : Color.white.opacity(0.14)))
                .scaleEffect(configuration.isPressed ? 0.95 : (isFocused ? 1.08 : 1))
                .animation(.easeOut(duration: 0.12), value: isFocused)
        }
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
    /// "2021 · 2h 10m · PG-13 · Drama, Thriller" (ratings show as logos in RatingsRow).
    var infoLine: String {
        var parts: [String] = []
        if let releaseInfo, !releaseInfo.isEmpty { parts.append(releaseInfo) }
        if let runtime, !runtime.isEmpty { parts.append(runtime) }
        if let ageRating, !ageRating.isEmpty { parts.append(ageRating) }
        if !genres.isEmpty { parts.append(genres.prefix(3).joined(separator: ", ")) }
        return parts.joined(separator: "  ·  ")
    }
}
