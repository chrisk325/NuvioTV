import SwiftUI
import NuvioShared

struct LibraryView: View {
    @EnvironmentObject private var app: AppModel
    private let columns = Array(repeating: GridItem(.fixed(220), spacing: 40), count: 7)

    var body: some View {
        if app.library.isEmpty && app.traktWatchlist.isEmpty {
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
                }
                .padding(.vertical, 60)
            }
        }
    }
}
