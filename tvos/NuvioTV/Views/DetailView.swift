import SwiftUI
import NuvioShared

/// Focus anchors on the details page. Moving up out of a horizontal row (cast, collection) nested
/// in the vertical scroll often finds nothing on tvOS when the target is off screen, so those rows
/// send focus to these explicitly.
enum DetailFocus: Hashable {
    case play, lastEpisode, people
}

extension View {
    /// Binds `.focused` only when a binding is given and the condition holds.
    @ViewBuilder
    func detailFocus(_ binding: FocusState<DetailFocus?>.Binding?, _ value: DetailFocus, when condition: Bool = true) -> some View {
        if let binding, condition {
            self.focused(binding, equals: value)
        } else {
            self
        }
    }

    /// Runs [action] when the user presses up while focus is inside this view.
    @ViewBuilder
    func onMoveUp(_ action: (() -> Void)?) -> some View {
        if let action {
            self.onMoveCommand { direction in
                if direction == .up { action() }
            }
        } else {
            self
        }
    }
}

struct DetailView: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    let preview: MetaPreview

    @State private var meta: Meta?
    @State private var error: String?
    @State private var selectedSeason: Int32 = 1
    @State private var details: DetailContent?
    @State private var ratings: [ExternalRating] = []
    @State private var trailer: PlaybackRequest?
    @State private var isLoadingTrailer = false
    @FocusState private var focus: DetailFocus?

    var body: some View {
        Group {
            if let meta {
                content(meta)
            } else if let error {
                // A focusable button keeps focus out of the tab bar, so Menu goes back.
                VStack(spacing: 30) {
                    MessageView(title: "Couldn't load details", message: error)
                        .frame(maxHeight: 500)
                    Button("Back") { dismiss() }
                }
            } else {
                ZStack {
                    FullScreenBackdrop(url: preview.backdropUrl, opacity: 0.3)
                    VStack(spacing: 30) {
                        LoadingView()
                            .frame(maxHeight: 300)
                        Button("Cancel") { dismiss() }
                    }
                }
            }
        }
        .onExitCommand { dismiss() }
        .task(id: preview.id) { await load() }
        .fullScreenCover(item: $trailer) { request in
            PlayerContainer(request: request).environmentObject(app)
        }
    }

    private func playTrailer(_ meta: Meta) {
        isLoadingTrailer = true
        Task { @MainActor in
            defer { isLoadingTrailer = false }
            let keys: [String] = [] // TMDB trailers are already merged into meta.trailerYtIds
            guard let url = (try? await app.core.trailerUrl(meta: meta, tmdbKeys: keys)) ?? nil else { return }
            var request = PlaybackRequest(url: url, headers: [:], meta: meta, video: nil, videoId: meta.id,
                                          title: "\(meta.name) · Trailer", startPositionMs: 0)
            request.isTrailer = true
            trailer = request
        }
    }

    private func infoLine(_ meta: Meta) -> String {
        var line = meta.infoLine
        if let tmdbRating = details?.tmdbRating {
            line += "  ·  TMDB \(tmdbRating)"
        }
        if meta.isSeriesLike, !meta.networks.isEmpty {
            line += "  ·  " + meta.networks.prefix(2).joined(separator: ", ")
        }
        return line
    }

    @MainActor
    private func load() async {
        guard meta == nil else { return }
        do {
            let loaded = try await app.core.meta(
                type: preview.apiType,
                id: preview.id,
                sourceAddonBaseUrl: preview.sourceAddonBaseUrl
            )
            let seasons = loaded.seasons().map { $0.int32Value }
            if let latest = app.core.latestProgressFor(contentId: loaded.id)?.season?.int32Value,
               seasons.contains(latest) {
                selectedSeason = latest
            } else {
                selectedSeason = seasons.first ?? 1
            }
            meta = loaded
            // Add-on meta shows immediately; the merged version (TMDB groups that are enabled,
            // add-on data for the rest) replaces it when ready. Kotlin suspend calls stay on the
            // main thread, so no `async let`.
            if let merged = try? await app.core.details(meta: loaded) {
                details = merged
                meta = merged.meta
            }
            ratings = (try? await app.core.ratings(meta: loaded)) ?? []
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func hasEpisodes(_ meta: Meta) -> Bool {
        meta.isSeriesLike && !meta.videos.isEmpty
    }

    /// Where "up" goes from rows below the cast: the cast row, else episodes, else Play.
    private func rowAboveTarget(_ meta: Meta) -> DetailFocus {
        if let people = details?.people, !people.isEmpty { return .people }
        return hasEpisodes(meta) ? .lastEpisode : .play
    }

    @ViewBuilder
    private func content(_ meta: Meta) -> some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 40) {
                hero(meta)
                if hasEpisodes(meta) {
                    EpisodesSection(meta: meta, selectedSeason: $selectedSeason, focus: $focus)
                }
                if let people = details?.people, !people.isEmpty {
                    PeopleRow(title: "Cast & crew", people: people, linksToTmdb: app.core.tmdb.isConfigured, focus: $focus)
                        .onMoveUp { focus = hasEpisodes(meta) ? .lastEpisode : .play }
                } else if !meta.cast.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Cast").font(.headline)
                        Text(meta.cast.prefix(12).joined(separator: ", "))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 80)
                }
                if !meta.director.isEmpty || !meta.writer.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        if !meta.director.isEmpty {
                            Text("Director: \(meta.director.joined(separator: ", "))")
                        }
                        if !meta.writer.isEmpty {
                            Text("Writer: \(meta.writer.prefix(4).joined(separator: ", "))")
                        }
                        if !meta.productionCompanies.isEmpty {
                            Text("Production: \(meta.productionCompanies.prefix(3).joined(separator: ", "))")
                        }
                    }
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 80)
                }
                if let details, !details.collectionParts.isEmpty {
                    MetaRowView(title: details.collectionName ?? "Collection", items: details.collectionParts)
                        .onMoveUp { focus = rowAboveTarget(meta) }
                }
                if let details, !details.recommendations.isEmpty {
                    MetaRowView(title: "More like this", items: details.recommendations)
                        // With a collection row above, let the focus engine move between the two rows.
                        .onMoveUp(details.collectionParts.isEmpty ? (({ focus = rowAboveTarget(meta) }) as () -> Void) : nil)
                }
            }
            .padding(.bottom, 80)
        }
        .background {
            FullScreenBackdrop(url: meta.backdropUrl) {
                LinearGradient(
                    colors: [.black.opacity(0.35), .black.opacity(0.85), .black],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
        }
    }

    @ViewBuilder
    private func hero(_ meta: Meta) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            if let logo = meta.logo {
                RemoteImage(url: logo, contentMode: .fit)
                    .frame(maxWidth: 600, maxHeight: 200, alignment: .leading)
            } else {
                Text(meta.name).font(.largeTitle).bold()
            }
            Text(infoLine(meta)).font(.callout).foregroundStyle(.secondary)
            if !ratings.isEmpty {
                RatingsRow(ratings: ratings)
            }
            if let tagline = details?.tagline {
                Text(tagline).font(.callout).italic().foregroundStyle(.secondary)
            }
            if let description = meta.description_, !description.isEmpty {
                Text(description)
                    .font(.body)
                    .lineLimit(5)
                    .frame(maxWidth: 1200, alignment: .leading)
            }
            HStack(spacing: 30) {
                playButton(meta)
                if !meta.isSeriesLike {
                    let watched = app.isWatched(meta, nil)
                    Button {
                        app.core.setWatched(meta: meta, video: nil, isWatched: !watched)
                    } label: {
                        Label(watched ? "Watched" : "Mark watched", systemImage: watched ? "eye.fill" : "eye")
                    }
                }
                if app.core.hasTrailerApi && !meta.trailerYtIds.isEmpty {
                    Button {
                        playTrailer(meta)
                    } label: {
                        Label(isLoadingTrailer ? "Loading…" : "Trailer", systemImage: "film")
                    }
                    .disabled(isLoadingTrailer)
                }
                Button {
                    app.toggleLibrary(meta)
                } label: {
                    Label(
                        app.isInLibrary(meta.id) ? "In Library" : "Add to Library",
                        systemImage: app.isInLibrary(meta.id) ? "checkmark" : "plus"
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
        }
        .padding(.horizontal, 80)
        .padding(.top, 300)
    }

    @ViewBuilder
    private func playButton(_ meta: Meta) -> some View {
        if meta.isSeriesLike && !meta.videos.isEmpty {
            if let action = nextEpisodeAction(meta) {
                NavigationLink(value: Route.streams(action.target)) {
                    Label(action.label, systemImage: "play.fill")
                }
                .focused($focus, equals: .play)
            }
        } else {
            let target = StreamTarget(meta: meta, video: nil)
            let progress = app.core.progressFor(videoId: target.videoId)
            NavigationLink(value: Route.streams(target)) {
                Label(progress?.isResumable == true ? "Resume" : "Play", systemImage: "play.fill")
            }
            .focused($focus, equals: .play)
        }
    }

    /// Resume the last-watched episode, or start the first watchable one.
    private func nextEpisodeAction(_ meta: Meta) -> EpisodeAction? {
        let episodes = meta.watchableEpisodes()
        if let last = app.core.latestProgressFor(contentId: meta.id),
           let index = episodes.firstIndex(where: { $0.id == last.videoId }) {
            if last.isCompleted, index + 1 < episodes.count {
                let next = episodes[index + 1]
                return EpisodeAction(target: StreamTarget(meta: meta, video: next), label: "Play \(next.episodeLabel ?? "next")")
            }
            let video = episodes[index]
            return EpisodeAction(target: StreamTarget(meta: meta, video: video), label: "Resume \(video.episodeLabel ?? "")")
        }
        guard let first = episodes.first ?? meta.videos.first else { return nil }
        return EpisodeAction(target: StreamTarget(meta: meta, video: first), label: "Play \(first.episodeLabel ?? "")")
    }
}

/// MDBList ratings as logos with scores (Android MDBListRatingsRow).
struct RatingsRow: View {
    let ratings: [ExternalRating]
    var iconHeight: CGFloat = 32

    var body: some View {
        HStack(spacing: 28) {
            ForEach(ratings, id: \.source) { rating in
                HStack(spacing: 10) {
                    Image(rating.icon)
                        .resizable()
                        .interpolation(.high)
                        .scaledToFit()
                        .frame(height: iconHeight)
                        .accessibilityLabel(rating.label)
                    Text(rating.value)
                        .font(.callout.weight(.semibold).monospacedDigit())
                }
            }
        }
    }
}

private struct EpisodeAction {
    let target: StreamTarget
    let label: String
}

struct EpisodesSection: View {
    @EnvironmentObject private var app: AppModel
    let meta: Meta
    @Binding var selectedSeason: Int32
    var focus: FocusState<DetailFocus?>.Binding? = nil

    var body: some View {
        let seasons = meta.seasons().map { $0.int32Value }
        VStack(alignment: .leading, spacing: 24) {
            if seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 20) {
                        ForEach(seasons, id: \.self) { season in
                            Button(season == 0 ? "Specials" : "Season \(season)") {
                                selectedSeason = season
                            }
                            .tint(season == selectedSeason ? .accentColor : nil)
                        }
                    }
                    .padding(.horizontal, 80)
                    .padding(.vertical, 20)
                }
                .focusSection()
            }

            let episodes = meta.episodesForSeason(season: selectedSeason)
            LazyVStack(alignment: .leading, spacing: 24) {
                ForEach(episodes, id: \.id) { video in
                    EpisodeRow(
                        meta: meta,
                        video: video,
                        progress: app.core.progressFor(videoId: video.id),
                        isWatched: app.isWatched(meta, video)
                    )
                    .detailFocus(focus, .lastEpisode, when: video.id == episodes.last?.id)
                    .contextMenu {
                        let watched = app.isWatched(meta, video)
                        Button(watched ? "Mark unwatched" : "Mark watched") {
                            app.core.setWatched(meta: meta, video: video, isWatched: !watched)
                        }
                    }
                }
            }
            .padding(.horizontal, 80)
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
        }
    }
}

struct EpisodeRow: View {
    let meta: Meta
    let video: Video
    let progress: WatchProgress?
    var isWatched = false

    var body: some View {
        NavigationLink(value: Route.streams(StreamTarget(meta: meta, video: video))) {
            HStack(alignment: .top, spacing: 30) {
                ZStack(alignment: .bottomLeading) {
                    RemoteImage(url: video.thumbnail ?? meta.backdropUrl)
                    if let progress {
                        GeometryReader { geo in
                            VStack {
                                Spacer()
                                Rectangle()
                                    .fill(Color.accentColor)
                                    .frame(width: geo.size.width * progress.fraction, height: 6)
                            }
                        }
                    }
                }
                .frame(width: 320, height: 180)
                .clipped()
                .cornerRadius(12)

                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) {
                        Text("\(video.episode?.int32Value ?? 0). \(video.title)")
                            .font(.headline)
                            .lineLimit(1)
                        if isWatched {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                        }
                    }
                    HStack(spacing: 16) {
                        if let released = video.released { Text(String(released.prefix(10))) }
                        if video.runtimeMinutes > 0 { Text("\(video.runtimeMinutes) min") }
                        if !video.isReleased { Text("Upcoming") }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    if let overview = video.overview {
                        Text(overview).font(.callout).lineLimit(3).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(20)
            .focusBorder()
        }
        .buttonStyle(.card)
    }
}
