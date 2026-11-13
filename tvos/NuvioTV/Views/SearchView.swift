import SwiftUI
import NuvioShared

struct SearchView: View {
    @EnvironmentObject private var app: AppModel
    @State private var query = ""
    @State private var results: [CatalogRow] = []
    @State private var isSearching = false
    @State private var searchedQuery = ""

    var body: some View {
        ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: 30) {
                if isSearching {
                    ProgressView().frame(maxWidth: .infinity).padding(60)
                } else if results.isEmpty && !searchedQuery.isEmpty {
                    MessageView(title: "No results for “\(searchedQuery)”", systemImage: "magnifyingglass")
                }
                ForEach(results, id: \.stableKey) { row in
                    MetaRowView(title: row.displayTitle, items: row.items)
                }
            }
            .padding(.vertical, 40)
        }
        .searchable(text: $query, prompt: "Movies, shows…")
        .task(id: query) {
            // Debounce typing on the on-screen keyboard.
            try? await Task.sleep(nanoseconds: 600_000_000)
            guard !Task.isCancelled else { return }
            await run(query)
        }
    }

    @MainActor
    private func run(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed != searchedQuery else { return }
        guard !trimmed.isEmpty else {
            results = []
            searchedQuery = ""
            return
        }
        isSearching = true
        defer { isSearching = false }
        let rows = (try? await app.core.search(query: trimmed)) ?? []
        // Ignore stale responses if the user kept typing.
        guard trimmed == query.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
        results = rows
        searchedQuery = trimmed
    }
}
