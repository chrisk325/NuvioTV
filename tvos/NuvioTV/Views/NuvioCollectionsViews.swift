import SwiftUI
import NuvioShared

/// A Nuvio collection on Home (Android collections, imported, read-only): its folders as tiles.
struct NuvioCollectionRow: View {
    let collection: NuvioCollection

    var body: some View {
        if !collection.folders.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                Text(collection.title).font(.headline).padding(.horizontal, 60)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 40) {
                        ForEach(collection.folders, id: \.id) { folder in
                            CollectionFolderTile(collection: collection, folder: folder)
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.vertical, 30)
                }
            }
            .focusSection()
        }
    }
}

/// Home rows for the imported collections: [pinned] ones go above the other rows, the rest below.
struct NuvioCollectionRows: View {
    @EnvironmentObject private var app: AppModel
    let pinned: Bool

    var body: some View {
        ForEach(app.nuvioCollections.filter { $0.pinToTop == pinned }, id: \.id) { collection in
            NuvioCollectionRow(collection: collection)
        }
    }
}

/// A folder tile: its cover image, else its emoji, else its name; square, poster or landscape.
struct CollectionFolderTile: View {
    let collection: NuvioCollection
    let folder: NuvioCollectionFolder

    private var size: CGSize {
        switch folder.shape {
        case "poster": return CGSize(width: 220, height: 330)
        case "landscape": return CGSize(width: 400, height: 225)
        default: return CGSize(width: 260, height: 260)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            NavigationLink(value: Route.collectionFolder(collection, folder)) {
                ZStack {
                    LinearGradient(colors: [Color.white.opacity(0.14), Color.white.opacity(0.05)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                    if let cover = folder.coverImageUrl, !cover.isEmpty {
                        RemoteImage(url: cover, maxPixel: ImageLoader.cardSize)
                    } else if let emoji = folder.coverEmoji, !emoji.isEmpty {
                        Text(emoji).font(.system(size: min(size.width, size.height) * 0.42))
                    } else {
                        Text(folder.title)
                            .font(.headline)
                            .multilineTextAlignment(.center)
                            .padding()
                    }
                }
                .frame(width: size.width, height: size.height)
                .clipped()
            }
            .buttonStyle(.card)

            if !folder.hideTitle {
                Text(folder.title)
                    .font(.caption)
                    .lineLimit(1)
                    .frame(width: size.width, alignment: .leading)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// A folder's page: a tab per source (plus "All"), or a row per source (Android FolderDetailScreen,
/// read-only). Sources: add-on catalogs, TMDB lists / collections / studios / networks / discover /
/// people, public Trakt lists.
struct CollectionFolderView: View {
    @EnvironmentObject private var app: AppModel
    let collection: NuvioCollection
    let folder: NuvioCollectionFolder

    @State private var items: [Int: [MetaPreview]] = [:]
    @State private var cursors: [Int: Int32] = [:]
    @State private var hasMore: [Int: Bool] = [:]
    @State private var loading: Set<Int> = []
    @State private var errors: [Int: String] = [:]
    @State private var selectedTab = 0

    private var sources: [NuvioCollectionSource] { folder.sources }
    /// Tab 0 is "All" when the collection shows it and there are several sources.
    private var hasAllTab: Bool { collection.showAllTab && sources.count > 1 }
    private var columns: [GridItem] { PosterGrid.columns(landscape: app.settings.modernLandscapePosters) }

    var body: some View {
        ZStack {
            FullScreenBackdrop(url: folder.heroBackdropUrl ?? collection.backdropImageUrl) {
                LinearGradient(colors: [.black.opacity(0.55), .black.opacity(0.9)], startPoint: .top, endPoint: .bottom)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    header
                    if sources.isEmpty {
                        MessageView(title: "This folder has no sources", systemImage: "square.stack")
                            .frame(maxWidth: .infinity)
                    } else if collection.showsRows {
                        ForEach(Array(sources.enumerated()), id: \.offset) { index, source in
                            sourceRow(index, source)
                        }
                    } else {
                        tabs
                        grid
                    }
                }
                .padding(.vertical, 30)
            }
        }
        .task {
            // Rows show every source; tabs load the first tab (all sources for "All").
            if collection.showsRows || hasAllTab {
                for index in sources.indices { await load(index) }
            } else {
                await load(0)
            }
        }
    }

    @ViewBuilder
    private var header: some View {
        if let logo = folder.titleLogoUrl, !logo.isEmpty {
            RemoteImage(url: logo, contentMode: .fit)
                .frame(maxWidth: 520, maxHeight: 150, alignment: .leading)
                .padding(.horizontal, 60)
        } else {
            Text(folder.title)
                .font(.title2.weight(.semibold))
                .padding(.horizontal, 60)
        }
    }

    // MARK: Rows mode

    @ViewBuilder
    private func sourceRow(_ index: Int, _ source: NuvioCollectionSource) -> some View {
        let title = app.core.collectionSourceTitle(source: source)
        if let rowItems = items[index], !rowItems.isEmpty {
            MetaRowView(title: title, items: rowItems, landscape: app.settings.modernLandscapePosters,
                        onReachEnd: { Task { await load(index) } })
        } else if let error = errors[index] {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.headline)
                Text(error).font(.callout).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 60)
        } else if loading.contains(index) {
            VStack(alignment: .leading) {
                Text(title).font(.headline)
                ProgressView().padding(30)
            }
            .padding(.horizontal, 60)
        }
    }

    // MARK: Tabs mode

    private var tabTitles: [String] {
        (hasAllTab ? ["All"] : []) + sources.map { app.core.collectionSourceTitle(source: $0) }
    }

    /// The sources a tab shows: every one for "All", else its own.
    private func sourceIndices(forTab tab: Int) -> [Int] {
        if hasAllTab { return tab == 0 ? Array(sources.indices) : [tab - 1] }
        return [tab]
    }

    private var tabs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 16) {
                ForEach(Array(tabTitles.enumerated()), id: \.offset) { index, title in
                    // Moving onto a tab switches to it, as with the season tabs.
                    Button { select(index) } label: {
                        Text(title)
                            .lineLimit(1)
                            .background(FocusReporter { select(index) })
                    }
                    .buttonStyle(SeasonTabStyle(isSelected: index == selectedTab))
                }
            }
            .padding(.horizontal, 60)
            .padding(.vertical, 16)
        }
        .focusSection()
    }

    private func select(_ tab: Int) {
        guard tab != selectedTab else { return }
        selectedTab = tab
        Task { for index in sourceIndices(forTab: tab) where items[index] == nil { await load(index) } }
    }

    /// "All": the sources' titles interleaved, one from each in turn, without repeats.
    private var gridItems: [MetaPreview] {
        let lists = sourceIndices(forTab: selectedTab).map { items[$0] ?? [] }
        if lists.count == 1 { return lists[0] }
        var seen = Set<String>()
        var result: [MetaPreview] = []
        let longest = lists.map(\.count).max() ?? 0
        for position in 0..<longest {
            for list in lists where position < list.count {
                if seen.insert(list[position].id).inserted { result.append(list[position]) }
            }
        }
        return result
    }

    @ViewBuilder
    private var grid: some View {
        let shown = gridItems
        let indices = sourceIndices(forTab: selectedTab)
        if shown.isEmpty {
            if indices.contains(where: { loading.contains($0) || items[$0] == nil }) && indices.contains(where: { errors[$0] == nil }) {
                ProgressView().frame(maxWidth: .infinity).padding(60)
            } else {
                MessageView(title: "Nothing here",
                            message: indices.compactMap { errors[$0] }.first,
                            systemImage: "square.stack")
                    .frame(maxWidth: .infinity)
            }
        } else {
            LazyVGrid(columns: columns, spacing: 50) {
                ForEach(Array(shown.enumerated()), id: \.element.id) { position, item in
                    PosterCard(item: item)
                        .onAppear {
                            if position >= shown.count - 8 {
                                Task { for index in indices { await load(index) } }
                            }
                        }
                }
            }
            .padding(60)
        }
    }

    // MARK: Loading

    /// The next page of source [index] (the first when nothing is loaded yet).
    @MainActor
    private func load(_ index: Int) async {
        guard index < sources.count, !loading.contains(index), hasMore[index] ?? true else { return }
        loading.insert(index)
        defer { loading.remove(index) }
        do {
            let page = try await app.core.collectionSourcePage(source: sources[index], cursor: cursors[index] ?? 0)
            let known = Set((items[index] ?? []).map(\.id))
            let fresh = page.items.filter { !known.contains($0.id) }
            items[index, default: []] += fresh
            cursors[index] = page.nextCursor
            hasMore[index] = page.hasMore && !fresh.isEmpty
            errors[index] = nil
        } catch {
            if items[index] == nil { errors[index] = error.localizedDescription; items[index] = [] }
            hasMore[index] = false
        }
    }
}

/// Settings > Home > Nuvio collections: import collections JSON (pasted, e.g. with the iPhone
/// keyboard, or a link to the file). Shown on Home read-only; never synced or edited here.
struct NuvioCollectionsSettingsView: View {
    @EnvironmentObject private var app: AppModel
    @State private var input = ""
    @State private var status: String?
    @State private var isImporting = false
    @State private var confirmRemove = false

    var body: some View {
        List {
            Section {
                TextField("Paste collections JSON or a link to it", text: $input)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Button(isImporting ? "Importing…" : "Import") { runImport() }
                    .disabled(isImporting || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if let status {
                    Text(status).foregroundStyle(.secondary)
                }
            } header: {
                Text("Import")
            } footer: {
                Text("Export collections from the Nuvio Android app (or its phone page) and paste the JSON here — the iPhone keyboard can paste it — or a link to a .json file. Importing replaces the collections imported before. They stay on this Apple TV only.")
            }

            if !app.nuvioCollections.isEmpty {
                Section("Imported") {
                    ForEach(app.nuvioCollections, id: \.id) { collection in
                        HStack {
                            Text(collection.title)
                            Spacer()
                            Text("\(collection.folders.count) folder\(collection.folders.count == 1 ? "" : "s")\(collection.pinToTop ? " · pinned" : "")")
                                .foregroundStyle(.secondary)
                        }
                    }
                    Button("Remove imported collections", role: .destructive) { confirmRemove = true }
                }
            }
        }
        .navigationTitle("Nuvio collections")
        .confirmationDialog("Remove the imported collections?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                app.core.removeNuvioCollections()
                status = nil
            }
        }
    }

    private func runImport() {
        let text = input
        isImporting = true
        status = nil
        Task { @MainActor in
            defer { isImporting = false }
            do {
                let count = try await app.core.importNuvioCollections(input: text).int32Value
                status = "Imported \(count) collection\(count == 1 ? "" : "s")."
                input = ""
            } catch {
                status = error.localizedDescription
            }
        }
    }
}
