import SwiftUI
import NuvioShared

/// Discover (Android's Discover screen): browse any installed add-on catalog, filtered by type,
/// catalog and genre, as an endless poster grid. The last choices are kept between launches.
struct DiscoverView: View {
    @EnvironmentObject private var app: AppModel

    @State private var slots: [CatalogSlot] = []
    @AppStorage("discover.type") private var type = "movie"
    @AppStorage("discover.catalog") private var catalogKey = ""
    @AppStorage("discover.genre") private var genre = ""

    @State private var items: [MetaPreview] = []
    @State private var nextSkip: Int32 = 0
    @State private var hasMore = true
    @State private var isLoading = false
    @State private var error: String?
    /// Changes with every new selection, so answers for an older one are dropped.
    @State private var requestId = 0

    private var columns: [GridItem] { PosterGrid.columns(landscape: app.settings.modernLandscapePosters) }

    private var types: [String] {
        var seen = Set<String>()
        return slots.map(\.catalog.apiType).filter { seen.insert($0).inserted }
    }
    private var catalogs: [CatalogSlot] { slots.filter { $0.catalog.apiType == type } }
    private var current: CatalogSlot? { catalogs.first { $0.key == catalogKey } ?? catalogs.first }
    private var genreRequired: Bool { current.map { app.core.genreRequired(slot: $0) } ?? false }
    private var genreOptions: [String] {
        guard let current else { return [] }
        let genres = app.core.genres(slot: current)
        if genres.isEmpty { return [] }
        return genreRequired ? genres : [""] + genres
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("Discover")
                    .font(.title2.weight(.semibold))
                    .padding(.horizontal, 60)
                    .padding(.top, 20)

                if slots.isEmpty {
                    MessageView(
                        title: "Nothing to discover",
                        message: "Install add-ons with catalogs in Add-ons to browse them here.",
                        systemImage: "safari"
                    )
                    .frame(maxWidth: .infinity)
                    .padding(.top, 60)
                } else {
                    filterRow(types, selected: type, label: typeLabel) { select(type: $0) }
                    filterRow(catalogs.map(\.key), selected: current?.key ?? "", label: catalogLabel) { select(catalog: $0) }
                    if !genreOptions.isEmpty {
                        filterRow(genreOptions, selected: genre, label: { $0.isEmpty ? "All genres" : $0 }) { select(genre: $0) }
                    }

                    if let error, items.isEmpty {
                        MessageView(title: "Couldn't load catalog", message: error)
                            .frame(maxWidth: .infinity)
                    } else if items.isEmpty && !isLoading && !hasMore {
                        Text("Nothing here.").foregroundStyle(.secondary).padding(60)
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
                    if isLoading { ProgressView().frame(maxWidth: .infinity).padding() }
                }
            }
        }
        .onAppear(perform: refreshSlots)
        .task(id: "\(current?.key ?? "")|\(genre)") { await restart() }
    }

    /// A row of pill buttons, scrolling sideways.
    private func filterRow(_ values: [String], selected: String, label: @escaping (String) -> String,
                           action: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(values, id: \.self) { value in
                    Button { action(value) } label: { Text(label(value)).lineLimit(1) }
                        .buttonStyle(SeasonTabStyle(isSelected: value == selected))
                }
            }
            .padding(.horizontal, 60)
            .padding(.vertical, 14)
        }
        .focusSection()
    }

    private func typeLabel(_ value: String) -> String {
        switch value {
        case "movie": return "Movies"
        case "series": return "Series"
        case "tv": return "TV"
        default: return value.prefix(1).uppercased() + value.dropFirst()
        }
    }

    private func catalogLabel(_ key: String) -> String {
        guard let slot = slots.first(where: { $0.key == key }) else { return key }
        return "\(slot.catalog.name) · \(slot.addon.displayName)"
    }

    /// Reads the catalogs again (add-ons may have changed) and keeps the choices valid.
    private func refreshSlots() {
        slots = app.core.discoverSlots()
        if !types.contains(type), let first = types.first { type = first }
        if !catalogs.contains(where: { $0.key == catalogKey }) { catalogKey = catalogs.first?.key ?? "" }
        fixGenre()
    }

    private func fixGenre() {
        let options = genreOptions
        if options.isEmpty { genre = "" }
        else if !options.contains(genre) { genre = options.first ?? "" }
    }

    private func select(type value: String) {
        guard value != type else { return }
        type = value
        catalogKey = catalogs.first?.key ?? ""
        fixGenre()
    }

    private func select(catalog key: String) {
        guard key != catalogKey || current?.key != key else { return }
        catalogKey = key
        genre = ""
        fixGenre()
    }

    private func select(genre value: String) {
        guard value != genre else { return }
        genre = value
    }

    @MainActor
    private func restart() async {
        requestId += 1
        items = []
        nextSkip = 0
        hasMore = current != nil
        error = nil
        isLoading = false
        await loadMore()
    }

    @MainActor
    private func loadMore() async {
        guard let slot = current, hasMore, !isLoading else { return }
        let id = requestId
        isLoading = true
        do {
            let row = try await app.core.loadCatalogFiltered(slot: slot, skip: nextSkip, genre: genre)
            guard id == requestId else { return }
            let known = Set(items.map(\.id))
            let fresh = row.items.filter { !known.contains($0.id) }
            items += fresh
            hasMore = row.hasMore && !fresh.isEmpty
            nextSkip = row.nextSkip
        } catch {
            guard id == requestId else { return }
            if !(error is CancellationError) { self.error = error.localizedDescription }
            hasMore = false
        }
        isLoading = false
    }
}
