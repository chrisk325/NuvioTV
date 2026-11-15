import SwiftUI
import NuvioShared

/// What to fetch streams for: a movie (video == nil) or one episode.
struct StreamTarget: Hashable {
    let meta: Meta
    let video: Video?

    var videoId: String { video?.id ?? meta.defaultVideoId }

    /// "tt…" / "tt…:season:episode" for Jellyfin / Emby, whatever id scheme the add-on uses.
    var serverVideoId: String? {
        guard let imdb = (meta.imdbId ?? (meta.id.hasPrefix("tt") ? meta.id : nil))?
            .components(separatedBy: ":").first else { return nil }
        if let video, let s = video.season?.int32Value, let e = video.episode?.int32Value {
            return "\(imdb):\(s):\(e)"
        }
        return imdb
    }

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
    /// "See all" of a Jellyfin / Emby library.
    case serverLibrary(ServerLibrary)
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
        case .serverLibrary(let library):
            ServerLibraryGridView(library: library)
        }
    }
}

extension View {
    /// Registers the app's navigation destinations on a NavigationStack root.
    func nuvioDestinations() -> some View {
        navigationDestination(for: Route.self) { RouteDestination(route: $0) }
    }
}
