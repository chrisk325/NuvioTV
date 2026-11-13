import SwiftUI
import NuvioShared

struct RemoteImage: View {
    let url: String?
    var contentMode: ContentMode = .fill

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { phase in
            switch phase {
            case .success(let image):
                image.resizable().aspectRatio(contentMode: contentMode)
            default:
                Rectangle().fill(Color.white.opacity(0.08))
            }
        }
    }
}

/// Poster card used in every row and grid. Navigates to the detail screen.
struct PosterCard: View {
    let item: MetaPreview
    var width: CGFloat = 220

    private var height: CGFloat {
        let ratio = CGFloat(item.posterShape.aspectRatio())
        return width / max(ratio, 0.1)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            NavigationLink(value: Route.detail(item)) {
                ZStack {
                    RemoteImage(url: item.poster ?? item.backdropUrl)
                    if item.poster == nil && item.backdropUrl == nil {
                        Text(item.name)
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .padding()
                    }
                }
                .frame(width: width, height: height)
                .clipped()
            }
            .buttonStyle(.card)

            Text(item.name)
                .font(.caption)
                .lineLimit(1)
                .frame(width: width, alignment: .leading)
                .foregroundStyle(.secondary)
        }
    }
}

/// Horizontal row of poster cards with a header.
struct MetaRowView: View {
    let title: String
    let items: [MetaPreview]
    var seeAll: Route? = nil
    var onReachEnd: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(title).font(.headline)
                Spacer()
                if let seeAll {
                    NavigationLink("See all", value: seeAll)
                        .font(.caption)
                }
            }
            .padding(.horizontal, 60)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 40) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        PosterCard(item: item)
                            .onAppear {
                                if index == items.count - 1 { onReachEnd?() }
                            }
                    }
                }
                .padding(.horizontal, 60)
                .padding(.vertical, 30)
            }
        }
        .focusSection()
    }
}

struct LoadingView: View {
    var label: String = "Loading…"
    var body: some View {
        VStack(spacing: 20) {
            ProgressView()
            Text(label).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct MessageView: View {
    let title: String
    var message: String? = nil
    var systemImage: String = "exclamationmark.triangle"

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: systemImage).font(.largeTitle)
            Text(title).font(.headline)
            if let message {
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 1000)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(80)
    }
}

extension Meta {
    /// "2021 · 2h 10m · ★ 7.4 · Drama, Thriller"
    var infoLine: String {
        var parts: [String] = []
        if let releaseInfo, !releaseInfo.isEmpty { parts.append(releaseInfo) }
        if let runtime, !runtime.isEmpty { parts.append(runtime) }
        if let ageRating, !ageRating.isEmpty { parts.append(ageRating) }
        if let imdbRating { parts.append("★ \(imdbRating)") }
        if !genres.isEmpty { parts.append(genres.prefix(3).joined(separator: ", ")) }
        return parts.joined(separator: "  ·  ")
    }
}
