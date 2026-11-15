import SwiftUI
import NuvioShared

struct LibraryView: View {
    @EnvironmentObject private var app: AppModel
    private var columns: [GridItem] { PosterGrid.columns(landscape: app.settings.modernLandscapePosters) }

    var body: some View {
        Group {
        if app.library.isEmpty && app.traktWatchlist.isEmpty && app.serverFavorites.isEmpty {
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
                    if !app.library.isEmpty {
                        Text("Saved on this Apple TV").font(.headline).padding(.horizontal, 60)
                        LazyVGrid(columns: columns, spacing: 50) {
                            ForEach(app.library, id: \.id) { item in
                                PosterCard(item: item.toPreview())
                            }
                        }
                        .padding(.horizontal, 60)
                    }
                    if !app.traktWatchlist.isEmpty {
                        Text("Trakt watchlist").font(.headline).padding(.horizontal, 60)
                        LazyVGrid(columns: columns, spacing: 50) {
                            ForEach(app.traktWatchlist, id: \.id) { item in
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
