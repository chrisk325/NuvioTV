import SwiftUI
import NuvioShared

/// Loaded catalog rows shared by the three Home layouts (Android HomeViewModel's catalog state).
@MainActor
final class HomeFeed: ObservableObject {
    struct RowState {
        var row: CatalogRow?
        var items: [MetaPreview] = []
        var nextSkip: Int32 = 0
        var hasMore = false
        var loaded = false
        var loading = false
    }

    @Published private(set) var rows: [String: RowState] = [:]

    func state(_ slot: CatalogSlot) -> RowState { rows[slot.key] ?? RowState() }

    func ensureLoaded(_ slot: CatalogSlot, core: NuvioCore) async {
        guard rows[slot.key]?.loaded != true, rows[slot.key]?.loading != true else { return }
        await load(slot, skip: 0, core: core)
    }

    func loadMore(_ slot: CatalogSlot, core: NuvioCore) async {
        let current = state(slot)
        guard current.hasMore, !current.loading else { return }
        await load(slot, skip: current.nextSkip, core: core)
    }

    /** Loads the first rows up front so the hero has items. */
    func preload(_ slots: [CatalogSlot], core: NuvioCore) async {
        for slot in slots.prefix(4) { await ensureLoaded(slot, core: core) }
    }

    func heroItems(_ slots: [CatalogSlot], core: NuvioCore) -> [MetaPreview] {
        let loadedRows = slots.compactMap { rows[$0.key]?.row }
        return core.heroItems(rows: loadedRows, limit: 10)
    }

    private func load(_ slot: CatalogSlot, skip: Int32, core: NuvioCore) async {
        var current = state(slot)
        current.loading = true
        rows[slot.key] = current
        do {
            let row = try await core.loadCatalog(slot: slot, skip: skip)
            let known = Set(current.items.map(\.id))
            current.items += row.items.filter { !known.contains($0.id) }
            current.hasMore = row.hasMore && !row.items.isEmpty
            current.nextSkip = row.nextSkip
            if current.row == nil { current.row = row }
        } catch {
            current.hasMore = false
        }
        current.loaded = true
        current.loading = false
        rows[slot.key] = current
    }
}

struct HomeView: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var feed = HomeFeed()

    var body: some View {
        let slots = app.homeSlots
        Group {
            if slots.isEmpty && app.serverLibraries.isEmpty {
                if app.isRefreshingAddons || (app.addons.isEmpty && app.failedAddonUrls.isEmpty) {
                    LoadingView(label: "Loading add-ons…")
                } else {
                    MessageView(
                        title: "No catalogs",
                        message: "Install or enable an add-on with catalogs in the Add-ons tab.",
                        systemImage: "square.grid.2x2"
                    )
                }
            } else {
                switch app.settings.homeLayout {
                case AppSettings.companion.LAYOUT_CLASSIC:
                    ClassicHomeView(slots: slots)
                case AppSettings.companion.LAYOUT_GRID:
                    GridHomeView(slots: slots)
                default:
                    ModernHomeView(slots: slots)
                }
            }
        }
        .environmentObject(feed)
        .task(id: slots.map(\.key).joined(separator: "|")) { await feed.preload(slots, core: app.core) }
    }
}

/// One home catalog as a poster row; loads when scrolled into view and pages at the end.
struct CatalogRowView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var feed: HomeFeed
    let slot: CatalogSlot
    var landscape = false
    var onFocus: ((MetaPreview) -> Void)? = nil

    var body: some View {
        let state = feed.state(slot)
        Group {
            if !state.items.isEmpty {
                MetaRowView(
                    title: slot.title,
                    items: state.items,
                    seeAll: .catalog(slot),
                    landscape: landscape,
                    onFocus: onFocus,
                    onReachEnd: { Task { await feed.loadMore(slot, core: app.core) } }
                )
            } else if !state.loaded {
                VStack(alignment: .leading) {
                    Text(slot.title).font(.headline).padding(.horizontal, 60)
                    ProgressView().padding(60)
                }
            }
            // Empty or failed rows collapse, as on Android.
        }
        .task(id: slot.key) { await feed.ensureLoaded(slot, core: app.core) }
    }
}

/// Long-press menu for Continue Watching cards.
struct ContinueWatchingMenu: View {
    let app: AppModel
    let progress: WatchProgress

    var body: some View {
        let preview = app.core.previewFor(progress: progress)
        let inLibrary = app.isInLibrary(progress.contentId)
        Button {
            app.core.markProgressWatched(progress: progress)
        } label: {
            Label("Mark as watched", systemImage: "eye")
        }
        Button {
            app.core.toggleLibraryPreview(preview: preview)
        } label: {
            Label(inLibrary ? "Remove from Library" : "Add to Library", systemImage: inLibrary ? "bookmark.slash" : "bookmark")
        }
        Button(role: .destructive) {
            app.core.removeProgress(contentId: progress.contentId)
        } label: {
            Label("Remove from Continue Watching", systemImage: "xmark.circle")
        }
    }
}

struct ContinueWatchingRow: View {
    @EnvironmentObject private var app: AppModel
    let items: [WatchProgress]
    var onFocus: ((MetaPreview) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Continue Watching").font(.headline).padding(.horizontal, 60)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 40) {
                    ForEach(items, id: \.videoId) { progress in
                        VStack(alignment: .leading, spacing: 12) {
                            NavigationLink(value: Route.detail(app.core.previewFor(progress: progress))) {
                                ZStack(alignment: .bottom) {
                                    RemoteImage(url: progress.background ?? progress.poster)
                                    GeometryReader { geo in
                                        VStack {
                                            Spacer()
                                            Rectangle()
                                                .fill(Color.accentColor)
                                                .frame(width: geo.size.width * progress.fraction, height: 6)
                                        }
                                    }
                                }
                                .frame(width: 400, height: 225)
                                .clipped()
                                .focusBorder()
                                .background(FocusReporter {
                                    guard let onFocus else { return }
                                    Task { @MainActor in onFocus(await app.continueWatchingPreview(progress)) }
                                })
                            }
                            .buttonStyle(.card)
                            .contextMenu { ContinueWatchingMenu(app: app, progress: progress) }
                            Text(subtitle(progress))
                                .font(.caption)
                                .lineLimit(1)
                                .frame(width: 400, alignment: .leading)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.horizontal, 60)
                .padding(.vertical, 30)
            }
        }
        .focusSection()
    }

    private func subtitle(_ p: WatchProgress) -> String {
        if let s = p.season?.int32Value, let e = p.episode?.int32Value {
            return "\(p.name) · S\(s) E\(e)"
        }
        return p.name
    }
}

/// "See all" grid for a single catalog, with paging.
struct CatalogGridView: View {
    @EnvironmentObject private var app: AppModel
    let slot: CatalogSlot

    @State private var items: [MetaPreview] = []
    @State private var nextSkip: Int32 = 0
    @State private var hasMore = true
    @State private var isLoading = false
    @State private var error: String?
    @State private var genre = ""

    private let columns = Array(repeating: GridItem(.fixed(220), spacing: 40), count: 7)

    var body: some View {
        ScrollView {
            let genres = app.core.genres(slot: slot)
            if !genres.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 20) {
                        ForEach([""] + genres, id: \.self) { option in
                            Button(option.isEmpty ? "All" : option) { select(genre: option) }
                                .tint(option == genre ? .accentColor : nil)
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.vertical, 20)
                }
                .focusSection()
            }
            if let error, items.isEmpty {
                MessageView(title: "Couldn't load catalog", message: error)
            }
            LazyVGrid(columns: columns, spacing: 50) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    PosterCard(item: item)
                        .onAppear {
                            if index >= items.count - 7 { Task { await loadMore() } }
                        }
                }
            }
            .padding(60)
            if isLoading { ProgressView().padding() }
        }
        .navigationTitle(slot.title)
        .task { if items.isEmpty { await loadMore() } }
    }

    private func select(genre option: String) {
        guard option != genre else { return }
        genre = option
        items = []
        nextSkip = 0
        hasMore = true
        error = nil
        Task { await loadMore() }
    }

    @MainActor
    private func loadMore() async {
        guard hasMore, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let row = try await app.core.loadCatalogFiltered(slot: slot, skip: nextSkip, genre: genre)
            let known = Set(items.map(\.id))
            let fresh = row.items.filter { !known.contains($0.id) }
            items += fresh
            hasMore = row.hasMore && !fresh.isEmpty
            nextSkip = row.nextSkip
        } catch {
            self.error = error.localizedDescription
            hasMore = false
        }
    }
}
