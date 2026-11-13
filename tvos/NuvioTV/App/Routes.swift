import SwiftUI
import NuvioShared

/// What to fetch streams for: a movie (video == nil) or one episode.
struct StreamTarget: Hashable {
    let meta: Meta
    let video: Video?

    var videoId: String { video?.id ?? meta.defaultVideoId }

    var title: String {
        guard let video, let label = video.episodeLabel else { return meta.name }
        return "\(meta.name) · \(label) · \(video.title)"
    }
}

enum Route: Hashable {
    case detail(MetaPreview)
    case streams(StreamTarget)
    case catalog(CatalogSlot)
    case person(Int32)
}

struct RouteDestination: View {
    let route: Route

    var body: some View {
        switch route {
        case .detail(let preview):
            DetailView(preview: preview)
        case .streams(let target):
            StreamsView(target: target)
        case .catalog(let slot):
            CatalogGridView(slot: slot)
        case .person(let id):
            PersonView(personId: id)
        }
    }
}

extension View {
    /// Registers the app's navigation destinations on a NavigationStack root.
    func nuvioDestinations() -> some View {
        navigationDestination(for: Route.self) { RouteDestination(route: $0) }
    }
}
