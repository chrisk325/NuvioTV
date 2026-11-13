import SwiftUI
import NuvioShared

struct LibraryView: View {
    @EnvironmentObject private var app: AppModel
    private let columns = Array(repeating: GridItem(.fixed(220), spacing: 40), count: 7)

    var body: some View {
        if app.library.isEmpty {
            MessageView(
                title: "Your library is empty",
                message: "Use “Add to Library” on any title to keep it here.",
                systemImage: "bookmark"
            )
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 50) {
                    ForEach(app.library, id: \.id) { item in
                        PosterCard(item: item.toPreview())
                    }
                }
                .padding(60)
            }
        }
    }
}
