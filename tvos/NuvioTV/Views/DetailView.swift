import SwiftUI
import NuvioShared

struct DetailView: View {
    @EnvironmentObject private var app: AppModel
    let preview: MetaPreview

    @State private var meta: Meta?
    @State private var error: String?
    @State private var selectedSeason: Int32 = 1
    @State private var enrichment: TmdbEnrichment?
    @State private var ratings: [ExternalRating] = []

    var body: some View {
        Group {
            if let meta {
                content(meta)
            } else if let error {
                MessageView(title: "Couldn't load details", message: error)
            } else {
                ZStack {
                    RemoteImage(url: preview.backdropUrl).opacity(0.3).ignoresSafeArea()
                    LoadingView()
                }
            }
        }
        .task(id: preview.id) { await load() }
    }

    private func infoLine(_ meta: Meta) -> String {
        var line = meta.infoLine
        if meta.ageRating == nil, let cert = enrichment?.certification {
            line = line.isEmpty ? cert : line + "  ·  " + cert
        }
        if let networks = enrichment?.networks, !networks.isEmpty, meta.isSeriesLike {
            line += "  ·  " + networks.prefix(2).joined(separator: ", ")
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
            // Kotlin suspend functions must be called on the main thread, so no `async let` here.
            enrichment = (try? await app.core.tmdbEnrichment(meta: loaded)) ?? nil
            ratings = (try? await app.core.ratings(meta: loaded)) ?? []
        } catch {
            self.error = error.localizedDescription
        }
    }

    @ViewBuilder
    private func content(_ meta: Meta) -> some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 40) {
                hero(meta)
                if meta.isSeriesLike && !meta.videos.isEmpty {
                    EpisodesSection(meta: meta, selectedSeason: $selectedSeason)
                }
                if let enrichment, !enrichment.cast.isEmpty {
                    PeopleRow(title: "Cast", people: enrichment.cast)
                } else if !meta.cast.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Cast").font(.headline)
                        Text(meta.cast.prefix(12).joined(separator: ", "))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 80)
                }
                if let enrichment, !enrichment.crew.isEmpty {
                    PeopleRow(title: "Crew", people: enrichment.crew)
                } else if !meta.director.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Director").font(.headline)
                        Text(meta.director.joined(separator: ", ")).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 80)
                }
                if let enrichment, !enrichment.collectionParts.isEmpty {
                    MetaRowView(title: enrichment.collectionName ?? "Collection", items: enrichment.collectionParts)
                }
                if let enrichment, !enrichment.recommendations.isEmpty {
                    MetaRowView(title: "More like this", items: enrichment.recommendations)
                }
            }
            .padding(.bottom, 80)
        }
        .background {
            RemoteImage(url: meta.backdropUrl)
                .overlay(
                    LinearGradient(
                        colors: [.black.opacity(0.35), .black.opacity(0.85), .black],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func hero(_ meta: Meta) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            if let logo = meta.logo ?? enrichment?.logo {
                RemoteImage(url: logo, contentMode: .fit)
                    .frame(maxWidth: 600, maxHeight: 200, alignment: .leading)
            } else {
                Text(meta.name).font(.largeTitle).bold()
            }
            Text(infoLine(meta)).font(.callout).foregroundStyle(.secondary)
            if !ratings.isEmpty {
                HStack(spacing: 16) {
                    ForEach(ratings, id: \.source) { rating in
                        Text("\(rating.label) \(rating.value)")
                            .font(.caption)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 6)
                            .background(Color.white.opacity(0.12), in: Capsule())
                    }
                }
            }
            if let tagline = enrichment?.tagline {
                Text(tagline).font(.callout).italic().foregroundStyle(.secondary)
            }
            if let description = meta.description_ ?? enrichment?.overview, !description.isEmpty {
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
                Button {
                    app.toggleLibrary(meta)
                } label: {
                    Label(
                        app.isInLibrary(meta.id) ? "In Library" : "Add to Library",
                        systemImage: app.isInLibrary(meta.id) ? "checkmark" : "plus"
                    )
                }
            }
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
            }
        } else {
            let target = StreamTarget(meta: meta, video: nil)
            let progress = app.core.progressFor(videoId: target.videoId)
            NavigationLink(value: Route.streams(target)) {
                Label(progress?.isResumable == true ? "Resume" : "Play", systemImage: "play.fill")
            }
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

private struct EpisodeAction {
    let target: StreamTarget
    let label: String
}

struct EpisodesSection: View {
    @EnvironmentObject private var app: AppModel
    let meta: Meta
    @Binding var selectedSeason: Int32

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

            LazyVStack(alignment: .leading, spacing: 24) {
                ForEach(meta.episodesForSeason(season: selectedSeason), id: \.id) { video in
                    EpisodeRow(
                        meta: meta,
                        video: video,
                        progress: app.core.progressFor(videoId: video.id),
                        isWatched: app.isWatched(meta, video)
                    )
                    .contextMenu {
                        let watched = app.isWatched(meta, video)
                        Button(watched ? "Mark unwatched" : "Mark watched") {
                            app.core.setWatched(meta: meta, video: video, isWatched: !watched)
                        }
                    }
                }
            }
            .padding(.horizontal, 80)
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
        }
        .buttonStyle(.card)
    }
}
