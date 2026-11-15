import SwiftUI
import NuvioShared

struct LibraryView: View {
    @EnvironmentObject private var app: AppModel
    private var columns: [GridItem] { PosterGrid.columns(landscape: app.settings.modernLandscapePosters) }

    /// Titles that are also a Jellyfin / Emby favourite show only under that server's favourites
    /// (adding to the Library with a server connected favourites it there).
    private var localItems: [SavedItem] {
        let onServer = app.serverFavoriteIds
        guard !onServer.isEmpty else { return app.library }
        return app.library.filter { !onServer.contains($0.id) }
    }

    private var watchlistItems: [MetaPreview] {
        let onServer = app.serverFavoriteIds
        guard !onServer.isEmpty else { return app.traktWatchlist }
        return app.traktWatchlist.filter { !onServer.contains($0.id) && !($0.imdbId.map(onServer.contains) ?? false) }
    }

    var body: some View {
        let saved = localItems
        let watchlist = watchlistItems
        Group {
        if saved.isEmpty && watchlist.isEmpty && app.serverFavorites.isEmpty {
            VStack(spacing: 30) {
                MessageView(
                    title: "Your library is empty",
                    message: "Use “Add to Library” on any title to keep it here. Connect Trakt in Settings to see your watchlist.",
                    systemImage: "bookmark"
                )
                NavigationLink("Release calendar") { CalendarView() }
            }
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 40) {
                    NavigationLink { CalendarView() } label: {
                        Label("Release calendar", systemImage: "calendar")
                    }
                    .padding(.horizontal, 60)
                    if !saved.isEmpty {
                        Text("Saved on this Apple TV").font(.headline).padding(.horizontal, 60)
                        LazyVGrid(columns: columns, spacing: 50) {
                            ForEach(saved, id: \.id) { item in
                                PosterCard(item: item.toPreview())
                            }
                        }
                        .padding(.horizontal, 60)
                    }
                    if !watchlist.isEmpty {
                        Text("Trakt watchlist").font(.headline).padding(.horizontal, 60)
                        LazyVGrid(columns: columns, spacing: 50) {
                            ForEach(watchlist, id: \.id) { item in
                                PosterCard(item: item)
                            }
                        }
                        .padding(.horizontal, 60)
                    }
                    // Favourites on Jellyfin / Emby servers.
                    ForEach(app.serverFavorites) { group in
                        Text("Favourites · \(group.serverName)").font(.headline).padding(.horizontal, 60)
                        LazyVGrid(columns: columns, spacing: 50) {
                            ForEach(group.items, id: \.id) { item in
                                PosterCard(item: item)
                            }
                        }
                        .padding(.horizontal, 60)
                    }
                }
                .padding(.vertical, 60)
            }
        }
        }
        // Server favourites change on the server, so re-read them whenever the tab opens.
        .task { await app.reloadServerFavorites() }
    }
}
