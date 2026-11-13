import SwiftUI
import NuvioShared

struct HomeView: View {
    @EnvironmentObject private var app: AppModel

    var body: some View {
        let slots = app.homeSlots
        Group {
            if slots.isEmpty {
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
                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: 30) {
                        if app.settings.showContinueWatching && !app.continueWatching.isEmpty {
                            ContinueWatchingRow(items: app.continueWatching)
                        }
                        ForEach(slots, id: \.key) { slot in
                            CatalogRowView(slot: slot)
                        }
                    }
                    .padding(.vertical, 40)
                }
            }
        }
    }
}

/// One home catalog. Loads itself when scrolled into view and pages when the end is reached.
struct CatalogRowView: View {
    @EnvironmentObject private var app: AppModel
    let slot: CatalogSlot

    @State private var items: [MetaPreview] = []
    @State private var nextSkip: Int32 = 0
    @State private var hasMore = false
    @State private var isLoading = false
    @State private var loaded = false
    @State private var failed = false

    var body: some View {
        Group {
            if !items.isEmpty {
                MetaRowView(
                    title: slot.title,
                    items: items,
                    seeAll: .catalog(slot),
                    onReachEnd: { Task { await loadMore() } }
                )
            } else if !loaded {
                VStack(alignment: .leading) {
                    Text(slot.title).font(.headline).padding(.horizontal, 60)
                    ProgressView().padding(60)
                }
            }
            // Empty or failed rows collapse, as on Android.
        }
        .task(id: slot.key) { await loadFirstPage() }
    }

    private func loadFirstPage() async {
        guard !loaded else { return }
        await load(skip: 0)
        loaded = true
    }

    private func loadMore() async {
        guard hasMore, !isLoading else { return }
        await load(skip: nextSkip)
    }

    @MainActor
    private func load(skip: Int32) async {
        isLoading = true
        defer { isLoading = false }
        do {
            let row = try await app.core.loadCatalog(slot: slot, skip: skip)
            let known = Set(items.map(\.id))
            items += row.items.filter { !known.contains($0.id) }
            hasMore = row.hasMore && !row.items.isEmpty
            nextSkip = row.nextSkip
        } catch {
            failed = true
            hasMore = false
        }
    }
}

struct ContinueWatchingRow: View {
    @EnvironmentObject private var app: AppModel
    let items: [WatchProgress]

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
                            }
                            .buttonStyle(.card)
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
