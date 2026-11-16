import SwiftUI
import NuvioShared

// The three Android home layouts (domain/model/HomeLayout: CLASSIC, GRID, MODERN).

/// Featured titles with auto-advance; left/right on the focused card changes the slide.
struct HeroCarousel: View {
    let items: [MetaPreview]
    var height: CGFloat = 620
    @State private var index = 0
    @State private var isFocused = false

    var body: some View {
        if items.isEmpty {
            EmptyView()
        } else {
            let item = items[min(index, items.count - 1)]
            VStack(alignment: .leading, spacing: 16) {
                NavigationLink(value: Route.detail(item)) {
                    // The backdrop fills (and overflows) the card, so it sits in an overlay of a
                    // fixed-size base; otherwise it grows the layout and pushes the info out of view.
                    Color.clear
                        .frame(maxWidth: .infinity)
                        .frame(height: height)
                        .overlay {
                            RemoteImage(url: item.backdropUrl ?? item.poster)
                                .id(item.id)
                                .transition(.opacity)
                        }
                        .overlay {
                            ZStack {
                                LinearGradient(colors: [.black.opacity(0.85), .clear], startPoint: .leading, endPoint: .trailing)
                                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .center, endPoint: .bottom)
                            }
                        }
                        .overlay(alignment: .bottomLeading) {
                            HeroInfo(item: item, maxWidth: 820, logoMaxHeight: 130, spacing: 14)
                                .padding(.horizontal, 50)
                                .padding(.bottom, 44)
                                .padding(.top, 30)
                        }
                        .clipped()
                    .background(FocusReporter { isFocused = true })
                }
                .buttonStyle(.card)
                .onMoveCommand { direction in
                    switch direction {
                    case .left: step(-1)
                    case .right: step(1)
                    default: break
                    }
                }
                HStack(spacing: 10) {
                    ForEach(0..<items.count, id: \.self) { i in
                        Capsule()
                            .fill(i == index ? Color.white : Color.white.opacity(0.3))
                            .frame(width: i == index ? 36 : 12, height: 8)
                    }
                }
                .padding(.leading, 20)
            }
            .padding(.horizontal, 60)
            .focusSection()
            .animation(.easeInOut(duration: 0.4), value: index)
            .task(id: items.map(\.id).joined()) {
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 8_000_000_000)
                    if !Task.isCancelled { step(1) }
                }
            }
        }
    }

    private func step(_ delta: Int) {
        guard !items.isEmpty else { return }
        index = (index + delta + items.count) % items.count
    }
}

/// Logo or title, info line and description for hero areas.
struct HeroInfo: View {
    let item: MetaPreview
    var maxWidth: CGFloat = 900
    var descriptionLines = 3
    var logoMaxHeight: CGFloat = 170
    var spacing: CGFloat = 18

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) {
            if let logo = item.logo {
                RemoteImage(url: logo, contentMode: .fit)
                    .frame(maxWidth: 520, maxHeight: logoMaxHeight, alignment: .leading)
            } else {
                Text(item.name).font(.system(size: 56, weight: .bold)).lineLimit(2)
            }
            Text(infoLine).font(.callout).foregroundStyle(.secondary)
            HeroRatings(item: item)
            if let description = item.description_, !description.isEmpty {
                Text(description).font(.callout).lineLimit(descriptionLines)
            }
        }
        .frame(maxWidth: maxWidth, alignment: .leading)
    }

    private var infoLine: String {
        var parts: [String] = []
        parts.append(item.apiType == "movie" ? "Movie" : "Series")
        if let year = item.releaseInfo, !year.isEmpty { parts.append(year) }
        // Ratings show as logos (MDBList, else the IMDb logo) in HeroRatings below, not as a star.
        if let runtime = item.runtime { parts.append(runtime) }
        if !item.genres.isEmpty { parts.append(item.genres.prefix(3).joined(separator: ", ")) }
        return parts.joined(separator: "  ·  ")
    }
}

/// MDBList rating logos for a hero item; loads (cached) when the item changes.
struct HeroRatings: View {
    @EnvironmentObject private var app: AppModel
    let item: MetaPreview
    @State private var ratings: [ExternalRating] = []
    @State private var loadedFor: String?

    var body: some View {
        Group {
            if !ratings.isEmpty && loadedFor == item.id {
                RatingsRow(ratings: ratings, iconHeight: 28)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: loadedFor)
        .task(id: item.id) {
            let id = item.id
            let result = (try? await app.core.heroRatings(preview: item)) ?? []
            guard !Task.isCancelled, id == item.id else { return }
            ratings = result
            loadedFor = id
        }
    }
}

// MARK: - Classic

/// Hero carousel, Continue Watching and poster rows over a backdrop of the focused title
/// (Android ClassicHomeContent + ClassicFocusGradientBackdrop).
struct ClassicHomeView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var feed: HomeFeed
    let slots: [CatalogSlot]
    /// Held in @State but not observed here: a focus move only redraws the backdrop, not
    /// every row of the (non-lazy) home stack.
    @State private var artwork = FocusedArtwork()
    @FocusState private var homeFocus: HomeFocus?
    @State private var lastContinueWatchingId: String?

    var body: some View {
        ZStack {
            FocusBackdrop(artwork: artwork)
            ScrollView(.vertical) {
                // Plain VStack: a LazyVStack unloads rows scrolled off screen, and tvOS then finds
                // nothing above on Up (first catalog → Continue Watching / hero).
                VStack(alignment: .leading, spacing: 30) {
                    if app.settings.showHero {
                        HeroCarousel(items: feed.heroItems(slots, core: app.core))
                    }
                    if showsContinueWatching {
                        ContinueWatchingRow(items: app.continueWatching, onFocus: setFocus, focus: $homeFocus,
                                            lastFocusedId: $lastContinueWatchingId)
                    }
                    ForEach(app.serverLibraries, id: \.key) { ServerLibraryRow(library: $0, onFocus: setFocus) }
                    ForEach(slots, id: \.key) { slot in
                        CatalogRowView(slot: slot, onFocus: setFocus,
                                       onMoveUp: upToContinueWatching(from: slot))
                    }
                }
                .padding(.vertical, 40)
            }
        }
    }

    private func setFocus(_ item: MetaPreview) {
        let url = item.backdropUrl ?? item.poster
        if artwork.url != url { artwork.url = url }
    }

    private var showsContinueWatching: Bool {
        app.settings.showContinueWatching && !app.continueWatching.isEmpty
    }

    /// First catalog right under Continue Watching: Up goes there explicitly.
    private func upToContinueWatching(from slot: CatalogSlot) -> (() -> Void)? {
        guard showsContinueWatching, app.serverLibraries.isEmpty,
              slot.key == firstLoadedSlotKey else { return nil }
        return {
            let items = app.continueWatching
            let id = items.first(where: { $0.videoId == lastContinueWatchingId })?.videoId ?? items.first?.videoId ?? ""
            homeFocus = .continueWatching(id)
        }
    }

    /// Empty or loading catalogs have no cards, so the first focusable row is the first with items.
    private var firstLoadedSlotKey: String? {
        slots.first { !feed.state($0).items.isEmpty }?.key
    }
}

/// The artwork URL of the focused Classic home card.
final class FocusedArtwork: ObservableObject {
    @Published var url: String?
}

/// Dimmed, blurred artwork of the focused title behind the rows. Waits until focus settles
/// (so scrolling quickly through a row doesn't load and crossfade every card's art) and shows
/// a small pre-blurred copy instead of blurring the whole screen live.
struct FocusBackdrop: View {
    @ObservedObject var artwork: FocusedArtwork
    @State private var image: UIImage?
    @State private var shownUrl: String?

    var body: some View {
        ZStack {
            Color.black
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                    .opacity(0.45)
                    .id(shownUrl)
                    .transition(.opacity)
            }
            LinearGradient(colors: [.black.opacity(0.2), .black.opacity(0.9)], startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .task(id: artwork.url) {
            guard let url = artwork.url, url != shownUrl else { return }
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled,
                  let blurred = await ImageLoader.shared.blurred(url),
                  !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.45)) {
                image = blurred
                shownUrl = url
            }
        }
    }
}

// MARK: - Grid

/// Every catalog as a titled section of wrapping poster grids ending with "See all"
/// (Android GridHomeContent).
struct GridHomeView: View {
    @EnvironmentObject private var app: AppModel
    let slots: [CatalogSlot]
    @EnvironmentObject private var feed: HomeFeed

    var body: some View {
        ScrollView(.vertical) {
            // Plain VStack: a LazyVStack unloads rows scrolled off screen, and tvOS then finds
            // nothing above on Up (first catalog → Continue Watching / hero).
            VStack(alignment: .leading, spacing: 50) {
                if app.settings.showHero {
                    HeroCarousel(items: feed.heroItems(slots, core: app.core), height: 520)
                }
                if app.settings.showContinueWatching && !app.continueWatching.isEmpty {
                    ContinueWatchingRow(items: app.continueWatching)
                }
                ForEach(app.serverLibraries, id: \.key) { ServerLibraryRow(library: $0) }
                ForEach(slots, id: \.key) { slot in
                    GridSection(slot: slot)
                }
            }
            .padding(.vertical, 40)
        }
    }
}

private struct GridSection: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var feed: HomeFeed
    let slot: CatalogSlot

    private var landscape: Bool { app.settings.modernLandscapePosters }
    private var columns: [GridItem] { PosterGrid.columns(landscape: landscape) }

    var body: some View {
        let state = feed.state(slot)
        Group {
            if !state.items.isEmpty {
                VStack(alignment: .leading, spacing: 20) {
                    Text(slot.titleFor(items: state.items)).font(.title3).bold().padding(.horizontal, 60)
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 40) {
                        // Posters + "See all" fill two rows: 13 + 1 of seven portrait, 7 + 1 of four landscape.
                        ForEach(Array(state.items.prefix(landscape ? 7 : 13).enumerated()), id: \.offset) { _, item in
                            PosterCard(item: item)
                        }
                        NavigationLink(value: Route.catalog(slot)) {
                            VStack(spacing: 12) {
                                Image(systemName: "square.grid.2x2").font(.largeTitle)
                                Text("See all").font(.headline)
                            }
                            .frame(width: landscape ? PosterGrid.landscapeWidth : PosterGrid.portraitWidth,
                                   height: landscape ? PosterGrid.landscapeWidth * 9 / 16 : 326)
                            .background(Color.white.opacity(0.08))
                        }
                        .buttonStyle(.card)
                    }
                    .padding(.horizontal, 60)
                }
                .focusSection()
            } else if !state.loaded {
                ProgressView().padding(60)
            }
        }
        .task(id: slot.key) { await feed.ensureLoaded(slot, core: app.core) }
    }
}

// MARK: - Modern

/// Large hero that follows focus, with poster rows below (landscape cards when Landscape
/// posters is on, which applies to every layout).
struct ModernHomeView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var feed: HomeFeed
    let slots: [CatalogSlot]
    @State private var focused: MetaPreview?
    @State private var pending: MetaPreview?
    @State private var debounce: Task<Void, Never>?
    @FocusState private var homeFocus: HomeFocus?
    @State private var lastContinueWatchingId: String?

    var body: some View {
        let hero = focused ?? feed.heroItems(slots, core: app.core).first
        ZStack(alignment: .topLeading) {
            // Full-screen artwork of the focused title.
            FullScreenBackdrop(url: hero?.backdropUrl, animated: false) {
                LinearGradient(colors: [.black.opacity(0.9), .black.opacity(0.2), .clear],
                               startPoint: .leading, endPoint: .trailing)
                LinearGradient(colors: [.clear, .black.opacity(0.65), .black],
                               startPoint: .top, endPoint: .bottom)
            }

            VStack(alignment: .leading, spacing: 0) {
                if let hero {
                    // Focusable hero: opens the title; left / right flick through the featured titles.
                    NavigationLink(value: Route.detail(hero)) {
                        // Compact so the poster rows get more of the screen.
                        HeroInfo(item: hero, maxWidth: 820, descriptionLines: 2, logoMaxHeight: 90, spacing: 10)
                            .padding(.horizontal, 30)
                            .padding(.vertical, 10)
                            .frame(maxWidth: 900, alignment: .leading)
                    }
                    .buttonStyle(ModernHeroStyle())
                    .focused($homeFocus, equals: .hero)
                    .onMoveCommand { direction in
                        switch direction {
                        case .left: stepHero(-1, from: hero)
                        case .right: stepHero(1, from: hero)
                        default: break
                        }
                    }
                    .padding(.horizontal, 50)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(height: 260, alignment: .topLeading)
                    .focusSection()
                }
                ScrollViewReader { proxy in
                ScrollView(.vertical) {
                    // Plain VStack: a LazyVStack unloads rows scrolled off screen, and tvOS then finds
                    // nothing above on Up (first catalog → Continue Watching / hero).
                    VStack(alignment: .leading, spacing: 24) {
                        Color.clear.frame(height: 0).id(Self.topAnchor)
                        if showsContinueWatching {
                            ContinueWatchingRow(
                                items: app.continueWatching,
                                onFocus: { item in
                                    setFocus(item)
                                    // Back on the top row: rows return to the top, right under the hero.
                                    withAnimation(.easeInOut(duration: 0.25)) { proxy.scrollTo(Self.topAnchor, anchor: .top) }
                                },
                                focus: $homeFocus,
                                lastFocusedId: $lastContinueWatchingId,
                                onMoveUp: { homeFocus = .hero }
                            )
                        }
                        ForEach(app.serverLibraries, id: \.key) { ServerLibraryRow(library: $0, onFocus: setFocus) }
                        ForEach(slots, id: \.key) { slot in
                            CatalogRowView(slot: slot, landscape: app.settings.modernLandscapePosters, onFocus: setFocus,
                                           onMoveUp: upToContinueWatching(from: slot))
                        }
                    }
                    .padding(.top, 30)
                    .padding(.bottom, 60)
                }
                // Rows fade out under the hero instead of being cut at a hard line.
                .mask {
                    VStack(spacing: 0) {
                        LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                            .frame(height: 70)
                        Color.black
                    }
                }
                }
            }
            // Hero sits higher, closer to the top bar (with a little gap), so the rows get more
            // of the screen.
            .padding(.top, -35)
        }
    }

    private var showsContinueWatching: Bool {
        app.settings.showContinueWatching && !app.continueWatching.isEmpty
    }

    /// First catalog: Up goes to Continue Watching, or to the hero when there is none.
    private func upToContinueWatching(from slot: CatalogSlot) -> (() -> Void)? {
        guard app.serverLibraries.isEmpty,
              slot.key == slots.first(where: { !feed.state($0).items.isEmpty })?.key
        else { return nil }
        guard showsContinueWatching else { return { homeFocus = .hero } }
        return {
            let items = app.continueWatching
            let id = items.first(where: { $0.videoId == lastContinueWatchingId })?.videoId ?? items.first?.videoId ?? ""
            homeFocus = .continueWatching(id)
        }
    }

    private static let topAnchor = "modern.top"

    /// Left / right on the focused hero: previous / next featured title.
    private func stepHero(_ offset: Int, from current: MetaPreview) {
        let items = feed.heroItems(slots, core: app.core)
        guard !items.isEmpty else { return }
        let index = items.firstIndex { $0.id == current.id } ?? -1
        let next = items[((index + offset) % items.count + items.count) % items.count]
        debounce?.cancel()
        focused = next
    }

    /// Debounced like Android (MODERN_HERO_FOCUS_DEBOUNCE_MS = 450) so fast scrolling doesn't flicker.
    private func setFocus(_ item: MetaPreview) {
        pending = item
        debounce?.cancel()
        debounce = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 450_000_000)
            guard !Task.isCancelled, let pending else { return }
            focused = pending
        }
    }
}

/// Modern hero when focused: a soft frosted panel behind the title info (no rim, no animation).
struct ModernHeroStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Panel(configuration: configuration)
    }

    private struct Panel: View {
        let configuration: ButtonStyle.Configuration
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .background(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(Color.black.opacity(isFocused ? 0.35 : 0))
                        .background(.ultraThinMaterial.opacity(isFocused ? 1 : 0),
                                    in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                )
                .opacity(configuration.isPressed ? 0.85 : 1)
        }
    }
}
