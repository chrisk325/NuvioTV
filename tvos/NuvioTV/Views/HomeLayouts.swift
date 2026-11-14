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
                    ZStack(alignment: .bottomLeading) {
                        RemoteImage(url: item.backdropUrl ?? item.poster)
                            .id(item.id)
                            .transition(.opacity)
                        LinearGradient(colors: [.black.opacity(0.85), .clear], startPoint: .leading, endPoint: .trailing)
                        LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .center, endPoint: .bottom)
                        HeroInfo(item: item, maxWidth: 820)
                            .padding(50)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: height)
                    .clipped()
                    .focusBorder()
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

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let logo = item.logo {
                RemoteImage(url: logo, contentMode: .fit)
                    .frame(maxWidth: 520, maxHeight: 170, alignment: .leading)
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
        if let rating = item.imdbRating { parts.append("★ \(rating)") }
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
    @State private var focused: MetaPreview?

    var body: some View {
        ZStack {
            FocusBackdrop(url: focused?.backdropUrl)
            ScrollView(.vertical) {
                // Plain VStack: a LazyVStack unloads rows scrolled off screen, and tvOS then finds
                // nothing above on Up (first catalog → Continue Watching / hero).
                VStack(alignment: .leading, spacing: 30) {
                    if app.settings.showHero {
                        HeroCarousel(items: feed.heroItems(slots, core: app.core))
                    }
                    if app.settings.showContinueWatching && !app.continueWatching.isEmpty {
                        ContinueWatchingRow(items: app.continueWatching, onFocus: setFocus)
                    }
                    ForEach(app.serverLibraries, id: \.key) { ServerLibraryRow(library: $0) }
                    ForEach(slots, id: \.key) { slot in
                        CatalogRowView(slot: slot, onFocus: setFocus)
                    }
                }
                .padding(.vertical, 40)
            }
        }
    }

    private func setFocus(_ item: MetaPreview) { focused = item }
}

/// Dimmed, blurred artwork of the focused title behind the rows.
struct FocusBackdrop: View {
    let url: String?

    var body: some View {
        FullScreenBackdrop(url: url, opacity: 0.45) {
            LinearGradient(colors: [.black.opacity(0.2), .black.opacity(0.9)], startPoint: .top, endPoint: .bottom)
        }
        .blur(radius: 30, opaque: true)
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

    private let columns = Array(repeating: GridItem(.fixed(220), spacing: 40), count: 7)

    var body: some View {
        let state = feed.state(slot)
        Group {
            if !state.items.isEmpty {
                VStack(alignment: .leading, spacing: 20) {
                    Text(slot.title).font(.title3).bold().padding(.horizontal, 60)
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 40) {
                        // 13 posters + "See all" fills two rows of seven.
                        ForEach(Array(state.items.prefix(13).enumerated()), id: \.offset) { _, item in
                            PosterCard(item: item)
                        }
                        NavigationLink(value: Route.catalog(slot)) {
                            VStack(spacing: 12) {
                                Image(systemName: "square.grid.2x2").font(.largeTitle)
                                Text("See all").font(.headline)
                            }
                            .frame(width: 220, height: 326)
                            .background(Color.white.opacity(0.08))
                            .focusBorder()
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

/// Large hero that follows focus, with poster rows below (landscape cards when enabled in
/// settings, like Android ModernHomeContent's modernLandscapePostersEnabled).
struct ModernHomeView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var feed: HomeFeed
    let slots: [CatalogSlot]
    @State private var focused: MetaPreview?
    @State private var pending: MetaPreview?
    @State private var debounce: Task<Void, Never>?

    var body: some View {
        let hero = focused ?? feed.heroItems(slots, core: app.core).first
        ZStack(alignment: .topLeading) {
            // Full-screen artwork of the focused title.
            FullScreenBackdrop(url: hero?.backdropUrl) {
                LinearGradient(colors: [.black.opacity(0.9), .black.opacity(0.2), .clear],
                               startPoint: .leading, endPoint: .trailing)
                LinearGradient(colors: [.clear, .black.opacity(0.65), .black],
                               startPoint: .top, endPoint: .bottom)
            }

            VStack(alignment: .leading, spacing: 0) {
                if let hero {
                    HeroInfo(item: hero, maxWidth: 820, descriptionLines: 3)
                        .padding(.horizontal, 80)
                        .padding(.top, 70)
                        .frame(height: 470, alignment: .topLeading)
                        .animation(.easeInOut(duration: 0.3), value: hero.id)
                }
                ScrollView(.vertical) {
                    // Plain VStack: a LazyVStack unloads rows scrolled off screen, and tvOS then finds
                    // nothing above on Up (first catalog → Continue Watching / hero).
                    VStack(alignment: .leading, spacing: 24) {
                        if app.settings.showContinueWatching && !app.continueWatching.isEmpty {
                            ContinueWatchingRow(items: app.continueWatching, onFocus: setFocus)
                        }
                        ForEach(app.serverLibraries, id: \.key) { ServerLibraryRow(library: $0) }
                        ForEach(slots, id: \.key) { slot in
                            CatalogRowView(slot: slot, landscape: app.settings.modernLandscapePosters, onFocus: setFocus)
                        }
                    }
                    .padding(.bottom, 60)
                }
            }
        }
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
