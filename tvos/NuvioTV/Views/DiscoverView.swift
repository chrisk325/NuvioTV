import SwiftUI
import NuvioShared

/// One browsable catalog in Discover: an add-on catalog or a Jellyfin / Emby library.
struct DiscoverCatalog: Identifiable {
    enum Source {
        case addon(CatalogSlot)
        /// [itemType]: "Movie" / "Series" for one half of a mixed library, nil otherwise.
        case server(ServerLibrary, itemType: String?)
    }

    let id: String
    /// "movie", "series", … (the type row).
    let type: String
    let name: String
    let source: Source

    /// "Name - Movie · Source", like the Home row headers.
    static func label(_ name: String, type: String, source: String) -> String {
        "\(name) - \(typeName(type)) · \(source)"
    }

    /// Singular type name for headers: "Movie", "Series", "Anime", …
    static func typeName(_ type: String) -> String {
        switch type {
        case "movie": return "Movie"
        case "series": return "Series"
        case "tv": return "TV"
        default: return type.prefix(1).uppercased() + type.dropFirst()
        }
    }

    static func from(library: ServerLibrary) -> [DiscoverCatalog] {
        func entry(_ id: String, _ type: String, _ itemType: String?) -> DiscoverCatalog {
            DiscoverCatalog(id: id, type: type, name: label(library.name, type: type, source: library.server.name),
                            source: .server(library, itemType: itemType))
        }
        // Collections libraries hold collections, not titles: not a Discover catalog.
        if library.isCollections { return [] }
        switch library.collectionType {
        case "movies": return [entry("server:\(library.key)", "movie", nil)]
        case "tvshows": return [entry("server:\(library.key)", "series", nil)]
        default:
            return [
                entry("server:\(library.key):movie", "movie", "Movie"),
                entry("server:\(library.key):series", "series", "Series"),
            ]
        }
    }
}

/// Discover (Android's Discover screen): browse any installed add-on catalog or Jellyfin / Emby
/// library, filtered by type, catalog and genre, as an endless poster grid. The last choices are
/// kept between launches.
struct DiscoverView: View {
    @EnvironmentObject private var app: AppModel

    @State private var slots: [DiscoverCatalog] = []
    /// Server library genres, loaded once per library.
    @State private var serverGenres: [String: [String]] = [:]
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
    /// Blurred artwork of the focused title behind the grid.
    @State private var backdrop: String?
    @State private var backdropTask: Task<Void, Never>?

    private var columns: [GridItem] { PosterGrid.columns(landscape: app.settings.modernLandscapePosters) }

    private var types: [String] {
        var seen = Set<String>()
        return slots.map(\.type).filter { seen.insert($0).inserted }
    }
    private var catalogs: [DiscoverCatalog] { slots.filter { $0.type == type } }
    private var current: DiscoverCatalog? { catalogs.first { $0.id == catalogKey } ?? catalogs.first }
    private var genreRequired: Bool {
        if case .addon(let slot)? = current?.source { return app.core.genreRequired(slot: slot) }
        return false
    }
    private var genreOptions: [String] {
        guard let current else { return [] }
        let genres: [String]
        switch current.source {
        case .addon(let slot): genres = app.core.genres(slot: slot)
        case .server(let library, _): genres = serverGenres[library.key] ?? []
        }
        if genres.isEmpty { return [] }
        return genreRequired ? genres : [""] + genres
    }
    private static let serverPageSize: Int32 = 60

    var body: some View {
        ZStack {
            // The focused title's artwork, blurred and dimmed so the filters and posters stay readable.
            FullScreenBackdrop(url: backdrop)
                .blur(radius: 40)
                .scaleEffect(1.15) // hides the blur's soft edges
                .overlay(Color.black.opacity(backdrop == nil ? 0 : 0.55).ignoresSafeArea())
                .ignoresSafeArea()
                .allowsHitTesting(false)
            content
        }
        .onChange(of: current?.id) { _, _ in backdrop = nil }
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("Discover")
                    .font(.title2.weight(.semibold))
                    .padding(.horizontal, 60)
                    .padding(.top, 20)

                if slots.isEmpty {
                    MessageView(
                        title: "Nothing to discover",
                        message: "Install add-ons with catalogs, or sign in to Jellyfin / Emby, to browse them here.",
                        systemImage: "safari"
                    )
                    .frame(maxWidth: .infinity)
                    .padding(.top, 60)
                } else {
                    filterRow(types, selected: type, label: typeLabel) { select(type: $0) }
                    filterRow(catalogs.map(\.id), selected: current?.id ?? "", label: catalogLabel) { select(catalog: $0) }
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
                            PosterCard(item: item, onFocus: { showBackdrop(for: $0) })
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
        .onChange(of: app.serverLibraries.map(\.key)) { _, _ in refreshSlots() }
        .task(id: current?.id ?? "") { await loadServerGenres() }
        .task(id: "\(current?.id ?? "")|\(genre)") { await restart() }
    }

    /// Follows focus, after a short pause so fast scrolling doesn't load every title's artwork.
    private func showBackdrop(for item: MetaPreview) {
        let url = item.backdropUrl
        backdropTask?.cancel()
        backdropTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 180_000_000)
            guard !Task.isCancelled else { return }
            backdrop = url
        }
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
        slots.first(where: { $0.id == key })?.name ?? key
    }

    /// Reads the catalogs again (add-ons or servers may have changed) and keeps the choices valid.
    private func refreshSlots() {
        let addonCatalogs = app.core.discoverSlots().map { slot in
            DiscoverCatalog(id: slot.key, type: slot.catalog.apiType,
                            name: DiscoverCatalog.label(slot.catalog.name, type: slot.catalog.apiType, source: slot.addon.displayName),
                            source: .addon(slot))
        }
        slots = addonCatalogs + app.serverLibraries.flatMap(DiscoverCatalog.from(library:))
        if !types.contains(type), let first = types.first { type = first }
        if !catalogs.contains(where: { $0.id == catalogKey }) { catalogKey = catalogs.first?.id ?? "" }
        fixGenre()
    }

    /// Genres for server libraries load asynchronously; an unknown genre waits for them.
    private func fixGenre() {
        if case .server(let library, _)? = current?.source, serverGenres[library.key] == nil { return }
        let options = genreOptions
        if options.isEmpty { genre = "" }
        else if !options.contains(genre) { genre = options.first ?? "" }
    }

    @MainActor
    private func loadServerGenres() async {
        guard case .server(let library, _)? = current?.source, serverGenres[library.key] == nil else { return }
        let genres = (try? await app.core.mediaServers.genres(library: library)) ?? []
        guard !Task.isCancelled else { return }
        serverGenres[library.key] = genres
        fixGenre()
    }

    private func select(type value: String) {
        guard value != type else { return }
        type = value
        catalogKey = catalogs.first?.id ?? ""
        fixGenre()
    }

    private func select(catalog key: String) {
        guard key != catalogKey || current?.id != key else { return }
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
        guard let catalog = current, hasMore, !isLoading else { return }
        let id = requestId
        isLoading = true
        do {
            let page: [MetaPreview]
            let more: Bool
            let next: Int32
            switch catalog.source {
            case .addon(let slot):
                let row = try await app.core.loadCatalogFiltered(slot: slot, skip: nextSkip, genre: genre)
                page = row.items
                more = row.hasMore
                next = row.nextSkip
            case .server(let library, let itemType):
                page = try await app.core.mediaServers.browse(library: library, start: nextSkip,
                                                              limit: Self.serverPageSize, genre: genre, itemType: itemType)
                more = Int32(page.count) >= Self.serverPageSize
                next = nextSkip + Int32(page.count)
            }
            guard id == requestId else { return }
            let known = Set(items.map(\.id))
            let fresh = page.filter { !known.contains($0.id) }
            items += fresh
            hasMore = more && !fresh.isEmpty
            nextSkip = next
        } catch {
            guard id == requestId else { return }
            if !(error is CancellationError) { self.error = error.localizedDescription }
            hasMore = false
        }
        isLoading = false
    }
}
