import SwiftUI
import NuvioShared

/// Focus anchors on the details page. Moving up out of a horizontal row (cast, collection) nested
/// in the vertical scroll often finds nothing on tvOS when the target is off screen, so those rows
/// send focus to these explicitly.
enum DetailFocus: Hashable {
    case play, people
    /// One per episode card (stable: every card always carries its own value).
    case episode(String)
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

    /// Runs [action] when the user presses Up on this view. Put it on the focusable control
    /// itself; a container never receives the press. The action runs a moment after the press:
    /// a focus change made while tvOS is still handling the same move is discarded.
    @ViewBuilder
    func onMoveUp(_ action: (() -> Void)?) -> some View {
        if let action {
            self.onMoveCommand { direction in
                guard direction == .up else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { action() }
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
    /// The episode card last focused; Up from the rows below returns to it.
    @State private var lastEpisodeId: String?

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
        // Full-screen details: the tab bar (Home / Search / … / Settings) is hidden here and
        // comes back on Menu.
        .toolbar(.hidden, for: .tabBar)
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
            // Fully watched check against the episode list (cards and the card menu use it).
            app.core.refreshSeriesWatched(meta: meta ?? loaded)
            ratings = (try? await app.core.ratings(meta: loaded)) ?? []
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func hasEpisodes(_ meta: Meta) -> Bool {
        meta.isSeriesLike && !meta.videos.isEmpty
    }

    /// Where "up" goes from rows below the cast: the cast row, else episodes, else Play.
    /// The last focused episode of the shown season, else its first episode.
    private func episodeAnchor(_ meta: Meta) -> DetailFocus {
        let episodes = meta.episodesForSeason(season: selectedSeason)
        let id = episodes.first(where: { $0.id == lastEpisodeId })?.id ?? episodes.first?.id ?? ""
        return .episode(id)
    }

    private func rowAboveTarget(_ meta: Meta) -> DetailFocus {
        if let people = details?.people, !people.isEmpty { return .people }
        return hasEpisodes(meta) ? episodeAnchor(meta) : .play
    }

    @ViewBuilder
    private func content(_ meta: Meta) -> some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 40) {
                hero(meta)
                if hasEpisodes(meta) {
                    EpisodesSection(meta: meta, selectedSeason: $selectedSeason, focus: $focus, lastFocusedId: $lastEpisodeId)
                }
                if let people = details?.people, !people.isEmpty {
                    PeopleRow(title: "Cast", people: people, linksToTmdb: app.core.tmdb.isConfigured, focus: $focus,
                              onMoveUp: { focus = hasEpisodes(meta) ? episodeAnchor(meta) : .play })
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
                    MetaRowView(title: details.collectionName ?? "Collection", items: details.collectionParts,
                                onMoveUp: { focus = rowAboveTarget(meta) })
                }
                if let details, !details.recommendations.isEmpty {
                    // With a collection row above, let the focus engine move between the two rows.
                    MetaRowView(title: "More like this", items: details.recommendations,
                                onMoveUp: details.collectionParts.isEmpty ? (({ focus = rowAboveTarget(meta) }) as () -> Void) : nil)
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

/// Episodes on the details page: centred logo, season tabs, then a horizontal row of large
/// episode cards (watched circle, "Episode N", title, synopsis, runtime · air date).
struct EpisodesSection: View {
    @EnvironmentObject private var app: AppModel
    let meta: Meta
    @Binding var selectedSeason: Int32
    var focus: FocusState<DetailFocus?>.Binding? = nil
    /// The episode card last focused (Up from the rows below returns to it).
    @Binding var lastFocusedId: String?

    var body: some View {
        let seasons = meta.seasons().map { $0.int32Value }
        let episodes = meta.episodesForSeason(season: selectedSeason)
        VStack(alignment: .leading, spacing: 28) {
            if let logo = meta.logo {
                RemoteImage(url: logo, contentMode: .fit)
                    .frame(maxWidth: 520, maxHeight: 110)
                    .frame(maxWidth: .infinity)
            }

            if seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        ForEach(seasons, id: \.self) { season in
                            // Moving onto a tab switches the season (as in the Apple TV app); clicking does too.
                            Button {
                                selectedSeason = season
                            } label: {
                                Text(season == 0 ? "Specials" : "Season \(season)")
                                    .background(FocusReporter {
                                        if selectedSeason != season { selectedSeason = season }
                                    })
                            }
                            .buttonStyle(SeasonTabStyle(isSelected: season == selectedSeason))
                        }
                    }
                    .padding(.horizontal, 80)
                    .padding(.vertical, 16)
                }
                .focusSection()
            }

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 40) {
                    ForEach(episodes, id: \.id) { video in
                        EpisodeCard(
                            meta: meta,
                            video: video,
                            progress: app.core.progressFor(videoId: video.id),
                            isWatched: app.isWatched(meta, video),
                            onFocus: { if lastFocusedId != video.id { lastFocusedId = video.id } }
                        )
                        // Same modifier on every card, always: a focus binding that moves between
                        // cards rebuilt the focused card and threw focus back to episode 1.
                        .detailFocus(focus, .episode(video.id))
                    }
                }
                .padding(.horizontal, 80)
                .padding(.vertical, 30)
            }
            .focusSection()
        }
        .onChange(of: selectedSeason) { _, _ in lastFocusedId = nil }
    }
}

/// Season tab: plain text, a soft tile when selected, white when focused.
struct SeasonTabStyle: ButtonStyle {
    let isSelected: Bool

    func makeBody(configuration: Configuration) -> some View {
        Tab(configuration: configuration, isSelected: isSelected)
    }

    private struct Tab: View {
        let configuration: ButtonStyle.Configuration
        let isSelected: Bool
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .font(.headline.weight(isSelected || isFocused ? .bold : .medium))
                .foregroundStyle(isFocused ? Color.black : Color.white.opacity(isSelected ? 1 : 0.8))
                .padding(.horizontal, 28)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(isFocused ? Color.white : (isSelected ? Color.white.opacity(0.16) : Color.clear))
                )
                .scaleEffect(configuration.isPressed ? 0.97 : (isFocused ? 1.06 : 1))
                .animation(.easeOut(duration: 0.12), value: isFocused)
        }
    }
}

/// Large 16:9 episode card with its details underneath.
struct EpisodeCard: View {
    @EnvironmentObject private var app: AppModel
    let meta: Meta
    let video: Video
    let progress: WatchProgress?
    var isWatched = false
    var onFocus: (() -> Void)? = nil

    private let width: CGFloat = 560

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            NavigationLink(value: Route.streams(StreamTarget(meta: meta, video: video))) {
                ZStack(alignment: .topLeading) {
                    RemoteImage(url: video.thumbnail ?? meta.backdropUrl)
                    // Watched state: empty ring, or a filled check.
                    Group {
                        if isWatched {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 34))
                                .foregroundStyle(.white, Color.accentColor)
                        } else {
                            Circle()
                                .strokeBorder(Color.white.opacity(0.85), lineWidth: 3)
                                .frame(width: 34, height: 34)
                        }
                    }
                    .shadow(color: .black.opacity(0.5), radius: 4)
                    .padding(16)
                    if let progress, progress.fraction > 0, !isWatched {
                        VStack {
                            Spacer()
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Rectangle().fill(Color.white.opacity(0.25))
                                    Rectangle().fill(Color.accentColor).frame(width: geo.size.width * progress.fraction)
                                }
                            }
                            .frame(height: 6)
                        }
                    }
                }
                .frame(width: width, height: width * 9 / 16)
                .clipped()
                .focusBorder()
                .background(FocusReporter { onFocus?() })
            }
            .buttonStyle(.card)
            .contextMenu {
                Button(isWatched ? "Mark unwatched" : "Mark watched") {
                    app.core.setWatched(meta: meta, video: video, isWatched: !isWatched)
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("Episode \(video.episode?.int32Value ?? 0)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(video.title)
                    .font(.headline)
                    .lineLimit(1)
                if let overview = video.overview, !overview.isEmpty {
                    Text(overview)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                let info = EpisodeFormat.info(runtimeMinutes: Int(video.runtimeMinutes), released: video.released, isReleased: video.isReleased)
                if !info.isEmpty {
                    Text(info)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: width, alignment: .leading)
        }
    }
}

enum EpisodeFormat {
    private static let input: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private static let output: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("d MMM yyyy")
        return f
    }()

    /// "1h 6m · 1 Sep 2022"; "Upcoming" when not yet released.
    static func info(runtimeMinutes: Int, released: String?, isReleased: Bool) -> String {
        var parts: [String] = []
        if runtimeMinutes > 0 {
            parts.append(runtimeMinutes >= 60 ? "\(runtimeMinutes / 60)h \(runtimeMinutes % 60)m" : "\(runtimeMinutes)m")
        }
        if let released, let date = input.date(from: String(released.prefix(10))) {
            parts.append(output.string(from: date))
        }
        if !isReleased { parts.append("Upcoming") }
        return parts.joined(separator: " · ")
    }
}
