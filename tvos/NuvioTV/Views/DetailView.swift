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

    /// Runs [action] when the user presses Down on this (focused) view.
    func onMoveDown(_ action: @escaping () -> Void) -> some View {
        onMoveCommand { direction in
            if direction == .down { action() }
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
    /// Continue Watching: open on this episode (its season, its card focused on the lower screen).
    var initialEpisode: EpisodeTarget? = nil

    @State private var meta: Meta?
    /// Episode card the episode row scrolls to (lazy rows only build cards near the visible ones).
    @State private var scrollToEpisode: String?
    @State private var error: String?
    @State private var selectedSeason: Int32 = 1
    @State private var details: DetailContent?
    @State private var ratings: [ExternalRating] = []
    @State private var trailer: PlaybackRequest?
    @State private var isLoadingTrailer = false
    @FocusState private var focus: DetailFocus?
    /// The episode card last focused; Up from the rows below returns to it.
    @State private var lastEpisodeId: String?
    /// Two screens, as in the Apple TV app: the hero (info + buttons over sharp artwork), and below
    /// it episodes / cast / more over blurred artwork. The logo glides between the two.
    @State private var showLower = false
    @Namespace private var logoSpace
    /// Episode whose full description is shown (tap and hold on an episode card).
    @State private var episodeInfo: Video?

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
        // Menu on the lower screen goes back to the hero first.
        .onExitCommand {
            if episodeInfo != nil {
                withAnimation(.spring(response: 0.45, dampingFraction: 0.9)) { episodeInfo = nil }
            } else if showLower {
                goToHero()
            } else {
                dismiss()
            }
        }
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
        if let tmdbRating = details?.tmdbRating, !ratings.contains(where: { $0.source == "tmdb" }) {
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
            let target = initialEpisode.flatMap { matchEpisode($0, in: loaded) }
            if let season = target?.season?.int32Value, seasons.contains(season) {
                selectedSeason = season
            } else if let latest = app.core.latestProgressFor(contentId: loaded.id)?.season?.int32Value,
               seasons.contains(latest) {
                selectedSeason = latest
            } else {
                selectedSeason = seasons.first ?? 1
            }
            meta = loaded
            if let target {
                // Straight to the episodes screen with that episode's card focused.
                lastEpisodeId = target.id
                scrollToEpisode = target.id
                showLower = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { focus = .episode(target.id) }
            }
            // Add-on meta shows immediately; the merged version (TMDB groups that are enabled,
            // add-on data for the rest) replaces it when ready. Kotlin suspend calls stay on the
            // main thread, so no `async let`.
            if let merged = try? await app.core.details(meta: loaded) {
                details = merged
                meta = merged.meta
            }
            // Fully watched check against the episode list (cards and the card menu use it).
            app.core.refreshSeriesWatched(meta: meta ?? loaded)
            ratings = (try? await app.core.ratings(meta: meta ?? loaded)) ?? []
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// The meta's episode for a Continue Watching entry: same id, else same season and episode
    /// (tracker ids and add-on ids can use different schemes).
    private func matchEpisode(_ target: EpisodeTarget, in meta: Meta) -> Video? {
        guard meta.isSeriesLike else { return nil }
        if let byId = meta.videos.first(where: { $0.id == target.videoId }) { return byId }
        guard let episode = target.episode else { return nil }
        return meta.videos.first { video in
            video.episode?.int32Value == episode && (target.season == nil || video.season?.int32Value == target.season)
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

    private func hasLowerContent(_ meta: Meta) -> Bool {
        hasEpisodes(meta) || !(details?.people.isEmpty ?? true) || !meta.cast.isEmpty
            || !meta.director.isEmpty || !meta.writer.isEmpty
            || !(details?.collectionParts.isEmpty ?? true) || !(details?.recommendations.isEmpty ?? true)
            || !(details?.facts.isEmpty ?? true)
    }

    private static let pageAnimation = Animation.spring(response: 0.55, dampingFraction: 0.9)

    /// Down from the hero buttons: slide to the episodes / cast screen.
    private func goToLower(_ meta: Meta) {
        guard !showLower, hasLowerContent(meta) else { return }
        withAnimation(Self.pageAnimation) { showLower = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            if hasEpisodes(meta) {
                focus = episodeAnchor(meta)
            } else if !(details?.people.isEmpty ?? true) {
                focus = .people
            }
        }
    }

    /// Up from the top row of the lower screen (or Menu): back to the hero.
    private func goToHero() {
        guard showLower else { return }
        withAnimation(Self.pageAnimation) { showLower = false }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { focus = .play }
    }

    /// Up from a lower row: the row above, or the hero when that row was the top one.
    private func moveUp(from meta: Meta) {
        let target = rowAboveTarget(meta)
        if target == .play { goToHero() } else { focus = target }
    }

    /// The title logo (or name), shared between the two screens so it glides from the hero's
    /// bottom-left to the top centre of the lower screen.
    @ViewBuilder
    private func titleLogo(_ meta: Meta, compact: Bool) -> some View {
        Group {
            if let logo = meta.logo {
                RemoteImage(url: logo, contentMode: .fit)
            } else {
                Text(meta.name)
                    .font(compact ? .title2 : .largeTitle)
                    .bold()
                    .lineLimit(2)
                    .multilineTextAlignment(compact ? .center : .leading)
            }
        }
        .frame(maxWidth: compact ? 460 : 600, maxHeight: compact ? 100 : 200,
               alignment: compact ? .center : .leading)
        .matchedGeometryEffect(id: "title-logo", in: logoSpace)
    }

    @ViewBuilder
    private func content(_ meta: Meta) -> some View {
        ZStack {
            // Hero screen: the artwork stays bright, with shading only behind the text (bottom
            // left). Lower screen: a separate blurred copy of the artwork crossfades in.
            FullScreenBackdrop(url: meta.backdropUrl) {
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.55), location: 0),
                        .init(color: .black.opacity(0.12), location: 0.45),
                        .init(color: .clear, location: 0.7)
                    ],
                    startPoint: .leading, endPoint: .trailing
                )
                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0.45),
                        .init(color: .black.opacity(0.65), location: 1)
                    ],
                    startPoint: .top, endPoint: .bottom
                )
            }
            .opacity(showLower ? 0 : 1)
            BlurredBackdrop(url: meta.backdropUrl)
                .opacity(showLower ? 1 : 0)

            if showLower {
                lowerScreen(meta)
                    .disabled(episodeInfo != nil)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                heroScreen(meta)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            if let video = episodeInfo {
                EpisodeInfoOverlay(meta: meta, video: video, isWatched: app.isWatched(meta, video)) {
                    withAnimation(.spring(response: 0.45, dampingFraction: 0.9)) { episodeInfo = nil }
                }
                .transition(.opacity)
                .zIndex(10)
            }
        }
    }

    /// First screen: title info and buttons at the bottom left over the sharp artwork.
    private func heroScreen(_ meta: Meta) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 0)
            hero(meta)
            if hasLowerContent(meta) {
                HStack(spacing: 10) {
                    Text(hasEpisodes(meta) ? "Episodes" : "More")
                    Image(systemName: "chevron.down")
                }
                .font(.callout.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 80)
                .padding(.top, 28)
            }
        }
        .padding(.bottom, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
    }

    /// Second screen: logo on top, then episodes, cast and the rest, over blurred artwork.
    private func lowerScreen(_ meta: Meta) -> some View {
        ScrollViewReader { proxy in
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 40) {
                titleLogo(meta, compact: true)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 20)
                if hasEpisodes(meta) {
                    EpisodesSection(meta: meta, selectedSeason: $selectedSeason, focus: $focus,
                                    scrollTarget: scrollToEpisode,
                                    onShowInfo: { video in
                                        withAnimation(.spring(response: 0.5, dampingFraction: 0.86)) { episodeInfo = video }
                                    },
                                    lastFocusedId: $lastEpisodeId, onMoveUpFromTop: { goToHero() })
                }
                if let people = details?.people, !people.isEmpty {
                    PeopleRow(title: "Cast", people: people, linksToTmdb: app.core.tmdb.isConfigured, focus: $focus,
                              onMoveUp: { if hasEpisodes(meta) { focus = episodeAnchor(meta) } else { goToHero() } })
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
                // Details table + related titles form their own "page": landing on it (the table
                // or the tabs) scrolls its top into view, so the table is never cut in half.
                if let details, !details.facts.isEmpty || !details.recommendations.isEmpty || !details.collectionParts.isEmpty {
                    let hasRelated = !details.recommendations.isEmpty || !details.collectionParts.isEmpty
                    let showPage = {
                        withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(Self.bottomPageId, anchor: .top) }
                    }
                    VStack(alignment: .leading, spacing: 40) {
                        if !details.facts.isEmpty {
                            DetailFactsTable(title: meta.isSeriesLike ? "Show Details" : "Movie Details", facts: details.facts,
                                             focusable: !hasRelated, onFocus: showPage, onMoveUp: { moveUp(from: meta) })
                        }
                        if hasRelated {
                            RelatedTitlesSection(details: details, onMoveUp: { moveUp(from: meta) }, onTabFocus: showPage)
                        }
                    }
                    .padding(.top, 40)
                    .id(Self.bottomPageId)
                }
            }
            .padding(.bottom, 80)
        }
        }
    }

    private static let bottomPageId = "detailsBottomPage"

    @ViewBuilder
    private func hero(_ meta: Meta) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            titleLogo(meta, compact: false)
            // White with a soft shadow so it reads on bright artwork (was the dim secondary grey).
            Text(infoLine(meta))
                .font(.callout.weight(.semibold))
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.85), radius: 6, x: 0, y: 1)
            if !ratings.isEmpty {
                RatingsRow(ratings: ratings)
                    .shadow(color: .black.opacity(0.85), radius: 6, x: 0, y: 1)
            }
            if let tagline = details?.tagline {
                Text(tagline).font(.callout).italic()
                    .foregroundStyle(.white.opacity(0.9))
                    .shadow(color: .black.opacity(0.85), radius: 6, x: 0, y: 1)
            }
            if let description = meta.description_, !description.isEmpty {
                ExpandableDescription(text: description)
            }
            // Down from any button slides to the episodes / cast screen (handled on each button:
            // move commands only reach the focused control).
            HStack(spacing: 30) {
                playButton(meta)
                    .onMoveDown { goToLower(meta) }
                if !meta.isSeriesLike {
                    let watched = app.isWatched(meta, nil)
                    Button {
                        app.core.setWatched(meta: meta, video: nil, isWatched: !watched)
                    } label: {
                        Label(watched ? "Watched" : "Mark watched", systemImage: watched ? "eye.fill" : "eye")
                    }
                    .onMoveDown { goToLower(meta) }
                }
                if app.core.hasTrailerApi && !meta.trailerYtIds.isEmpty {
                    Button {
                        playTrailer(meta)
                    } label: {
                        Label(isLoadingTrailer ? "Loading…" : "Trailer", systemImage: "film")
                    }
                    .disabled(isLoadingTrailer)
                    .onMoveDown { goToLower(meta) }
                }
                Button {
                    app.toggleLibrary(meta)
                } label: {
                    Label(
                        app.isInLibrary(meta.id) ? "In Library" : "Add to Library",
                        systemImage: app.isInLibrary(meta.id) ? "checkmark" : "plus"
                    )
                }
                .onMoveDown { goToLower(meta) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
        }
        .padding(.horizontal, 80)
        // Play first, not the "Show more" pill above it.
        .defaultFocus($focus, .play)
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
        // Narrow columns (the Classic hero) used to wrap "96%" so the % dropped
        // to its own line: values never wrap, and the gaps tighten to fit instead.
        ViewThatFits(in: .horizontal) {
            row(spacing: 28, inner: 10)
            row(spacing: 18, inner: 8)
            row(spacing: 12, inner: 6)
        }
    }

    private func row(spacing: CGFloat, inner: CGFloat) -> some View {
        HStack(spacing: spacing) {
            ForEach(ratings, id: \.source) { rating in
                HStack(spacing: inner) {
                    // Original colours always: inside a focusable button (the Modern Home hero)
                    // tvOS would otherwise draw the logo as a flat tinted shape.
                    Image(rating.icon)
                        .renderingMode(.original)
                        .resizable()
                        .interpolation(.high)
                        .scaledToFit()
                        .frame(height: iconHeight)
                        .accessibilityLabel(rating.label)
                    Text(rating.value)
                        .font(.callout.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .fixedSize()
                }
                .fixedSize()
            }
        }
    }
}

private struct EpisodeAction {
    let target: StreamTarget
    let label: String
}

/// Episodes on the details page: season tabs, then a horizontal row of large
/// episode cards (watched circle, "Episode N", title, synopsis, runtime · air date).
struct EpisodesSection: View {
    @EnvironmentObject private var app: AppModel
    let meta: Meta
    @Binding var selectedSeason: Int32
    var focus: FocusState<DetailFocus?>.Binding? = nil
    /// Episode card to scroll into view (Continue Watching opens the page on it).
    var scrollTarget: String? = nil
    /// Tap and hold on an episode: show its full description.
    var onShowInfo: ((Video) -> Void)? = nil
    /// The episode card last focused (Up from the rows below returns to it).
    @Binding var lastFocusedId: String?
    /// Up from the top row (season tabs, else the episode cards): back to the hero screen.
    var onMoveUpFromTop: (() -> Void)? = nil

    var body: some View {
        let seasons = meta.seasons().map { $0.int32Value }
        let episodes = meta.episodesForSeason(season: selectedSeason)
        VStack(alignment: .leading, spacing: 28) {
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
                            .onMoveUp(onMoveUpFromTop)
                        }
                    }
                    .padding(.horizontal, 80)
                    .padding(.vertical, 16)
                }
                .focusSection()
            }

            ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 40) {
                    ForEach(episodes, id: \.id) { video in
                        EpisodeCard(
                            meta: meta,
                            video: video,
                            progress: app.core.progressFor(videoId: video.id),
                            isWatched: app.isWatched(meta, video),
                            onFocus: { if lastFocusedId != video.id { lastFocusedId = video.id } },
                            onLongPress: { onShowInfo?(video) }
                        )
                        // Same modifier on every card, always: a focus binding that moves between
                        // cards rebuilt the focused card and threw focus back to episode 1.
                        .detailFocus(focus, .episode(video.id))
                        .onMoveUp(seasons.count > 1 ? nil : onMoveUpFromTop)
                        .id(video.id)
                    }
                }
                .padding(.horizontal, 80)
                .padding(.vertical, 30)
            }
            .focusSection()
            .onAppear { scroll(proxy, to: scrollTarget) }
            .onChange(of: scrollTarget) { _, id in scroll(proxy, to: id) }
            }
        }
        .onChange(of: selectedSeason) { _, _ in lastFocusedId = nil }
    }

    private func scroll(_ proxy: ScrollViewProxy, to id: String?) {
        guard let id, meta.episodesForSeason(season: selectedSeason).contains(where: { $0.id == id }) else { return }
        DispatchQueue.main.async { proxy.scrollTo(id, anchor: .leading) }
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
    /// Tap and hold: full description card (Mark watched lives there).
    var onLongPress: (() -> Void)? = nil

    private let width: CGFloat = 560

    var body: some View {
        // Room for the focused card's lift (it grows ~10% and would touch the text).
        VStack(alignment: .leading, spacing: 30) {
            NavigationLink(value: Route.streams(StreamTarget(meta: meta, video: video))) {
                ZStack(alignment: .topLeading) {
                    RemoteImage(url: video.thumbnail ?? meta.backdropUrl, maxPixel: ImageLoader.wideCardSize)
                    // Watched state: empty ring, or a filled check.
                    Group {
                        if isWatched {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 34))
                                .foregroundStyle(.white, Color(white: 0.38))
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
                .background(FocusReporter { onFocus?() })
            }
            .buttonStyle(.card)
            // A long-press gesture never fires on a tvOS button (the button keeps the press), so
            // tap and hold opens the system context menu instead.
            .contextMenu {
                if let onLongPress {
                    Button {
                        // Let the menu close before the overlay animates in.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { onLongPress() }
                    } label: {
                        Label("Full description", systemImage: "text.alignleft")
                    }
                }
                Button {
                    app.core.setWatched(meta: meta, video: video, isWatched: !isWatched)
                } label: {
                    Label(isWatched ? "Mark as unwatched" : "Mark as watched",
                          systemImage: isWatched ? "eye.slash" : "eye")
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

/// The artwork heavily blurred and lightly dimmed, edge to edge (enlarged so the blur doesn't
/// darken the edges). Background of the details page's lower screen.
struct BlurredBackdrop: View {
    let url: String?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                if let url {
                    RemoteImage(url: url)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                        .scaleEffect(1.25)
                        .blur(radius: 50)
                }
                Color.black.opacity(0.22)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .clipped()
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

/// Tap-and-hold episode info: the episode's thumbnail fills the screen blurred behind a card with
/// the thumbnail, title, runtime / date and the full description, plus Play and Mark watched.
struct EpisodeInfoOverlay: View {
    @EnvironmentObject private var app: AppModel
    let meta: Meta
    let video: Video
    let isWatched: Bool
    let onClose: () -> Void
    @FocusState private var playFocused: Bool
    @State private var appeared = false

    var body: some View {
        ZStack {
            BlurredBackdrop(url: video.thumbnail ?? meta.backdropUrl)
                .opacity(appeared ? 1 : 0)

            HStack(alignment: .top, spacing: 50) {
                RemoteImage(url: video.thumbnail ?? meta.backdropUrl, maxPixel: ImageLoader.wideCardSize)
                    .frame(width: 640, height: 360)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .shadow(color: .black.opacity(0.5), radius: 20, y: 10)

                VStack(alignment: .leading, spacing: 16) {
                    Text(episodeHeading)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(video.title)
                        .font(.title2.bold())
                        .lineLimit(2)
                    let info = EpisodeFormat.info(runtimeMinutes: Int(video.runtimeMinutes), released: video.released, isReleased: video.isReleased)
                    if !info.isEmpty {
                        Text(info).font(.callout).foregroundStyle(.secondary)
                    }
                    if let overview = video.overview, !overview.isEmpty {
                        ScrollView {
                            Text(overview)
                                .font(.body)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(maxHeight: 360)
                    } else {
                        Text("No description for this episode.").foregroundStyle(.secondary)
                    }
                    HStack(spacing: 24) {
                        NavigationLink(value: Route.streams(StreamTarget(meta: meta, video: video))) {
                            Label("Play", systemImage: "play.fill")
                        }
                        .focused($playFocused)
                        Button {
                            app.core.setWatched(meta: meta, video: video, isWatched: !isWatched)
                        } label: {
                            Label(isWatched ? "Mark unwatched" : "Mark watched",
                                  systemImage: isWatched ? "eye.slash" : "eye")
                        }
                        Button("Close", action: onClose)
                    }
                    .padding(.top, 8)
                }
                .frame(maxWidth: 820, alignment: .leading)
            }
            .padding(50)
            .background(
                RoundedRectangle(cornerRadius: 36, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .overlay(RoundedRectangle(cornerRadius: 36, style: .continuous).fill(Color.black.opacity(0.25)))
            )
            .scaleEffect(appeared ? 1 : 0.88)
            .opacity(appeared ? 1 : 0)
            .focusSection()
        }
        .onAppear {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.86)) { appeared = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { playFocused = true }
        }
    }

    private var episodeHeading: String {
        var parts: [String] = []
        if let e = video.episode?.int32Value { parts.append("Episode \(e)") }
        if let s = video.season?.int32Value { parts.append(s == 0 ? "Specials" : "Season \(s)") }
        return parts.joined(separator: " · ")
    }
}

/// Details description folded to three lines, with a "Show more" pill (only when the text is
/// actually cut off) that unfolds the full text; "Show less" folds it again.
struct ExpandableDescription: View {
    let text: String
    var lines = 3
    @State private var expanded = false
    @State private var truncated = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(text)
                .font(.body)
                .foregroundStyle(.white)
                .lineLimit(expanded ? nil : lines)
                .fixedSize(horizontal: false, vertical: expanded)
                .frame(maxWidth: 1200, alignment: .leading)
                .shadow(color: .black.opacity(0.85), radius: 6, x: 0, y: 1)
                .background(
                    // The full text, laid out at the same width: taller than the folded text means it's cut off.
                    GeometryReader { folded in
                        Text(text)
                            .font(.body)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(width: folded.size.width, alignment: .leading)
                            .hidden()
                            .background(GeometryReader { full in
                                Color.clear
                                    .onAppear { measure(full: full.size.height, folded: folded.size.height) }
                                    .onChange(of: full.size.height) { _, height in measure(full: height, folded: folded.size.height) }
                            })
                    }
                    .hidden()
                )
            if truncated || expanded {
                Button {
                    withAnimation(.easeInOut(duration: 0.3)) { expanded.toggle() }
                } label: {
                    Label(expanded ? "Show less" : "Show more", systemImage: expanded ? "chevron.up" : "chevron.down")
                }
                .buttonStyle(SeeAllButtonStyle())
            }
        }
    }

    private func measure(full: CGFloat, folded: CGFloat) {
        guard !expanded else { return }
        let cut = full > folded + 2
        if truncated != cut { truncated = cut }
    }
}

/// "Movie Details" / "Show Details": label on the left, value on the right, hairlines between rows.
struct DetailFactsTable: View {
    let title: String
    let facts: [DetailFact]
    /// Focusable when nothing focusable follows it (no related titles), so it can be reached.
    var focusable = false
    var onFocus: (() -> Void)? = nil
    var onMoveUp: (() -> Void)? = nil
    @FocusState private var focused: Bool

    var body: some View {
        if focusable {
            table
                .focusable()
                .focused($focused)
                .onChange(of: focused) { _, isFocused in if isFocused { onFocus?() } }
                .onMoveUp(onMoveUp)
        } else {
            table
        }
    }

    private var table: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(.title3.weight(.bold)).padding(.bottom, 18)
            ForEach(Array(facts.enumerated()), id: \.offset) { index, fact in
                HStack(alignment: .firstTextBaseline, spacing: 40) {
                    Text(fact.label)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.7))
                    Spacer(minLength: 20)
                    Text(fact.value)
                        .font(.callout)
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.trailing)
                        .lineLimit(2)
                }
                .padding(.vertical, 16)
                if index < facts.count - 1 {
                    Rectangle().fill(Color.white.opacity(0.12)).frame(height: 1)
                }
            }
        }
        .frame(maxWidth: 1100, alignment: .leading)
        .padding(.horizontal, 80)
        .shadow(color: .black.opacity(0.6), radius: 4, x: 0, y: 1)
    }
}

/// "Collection | More Like This": tabs that switch the poster row below (moving onto a tab
/// selects it, as with the season tabs).
struct RelatedTitlesSection: View {
    let details: DetailContent
    var onMoveUp: (() -> Void)? = nil
    /// A tab got focus (scrolls the details table above into view with it).
    var onTabFocus: (() -> Void)? = nil
    @State private var tab: Tab?

    enum Tab: Hashable { case collection, related }

    private var tabs: [Tab] {
        var result: [Tab] = []
        if !details.collectionParts.isEmpty { result.append(.collection) }
        if !details.recommendations.isEmpty { result.append(.related) }
        return result
    }

    private func label(_ tab: Tab) -> String {
        switch tab {
        case .collection: return "Collection"
        case .related: return "More Like This"
        }
    }

    var body: some View {
        let current = tab ?? tabs.first ?? .related
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                ForEach(Array(tabs.enumerated()), id: \.element) { index, item in
                    if index > 0 {
                        Text("|").font(.title3).foregroundStyle(.white.opacity(0.4))
                    }
                    Button { tab = item } label: {
                        Text(label(item))
                            .background(FocusReporter {
                                if tab != item { tab = item }
                                onTabFocus?()
                            })
                    }
                    .buttonStyle(RelatedTabStyle(isSelected: item == current))
                    .onMoveUp(onMoveUp)
                }
            }
            .padding(.horizontal, 64)
            .focusSection()
            if current == .collection, let name = details.collectionName {
                Text(name).font(.callout).foregroundStyle(.secondary).padding(.horizontal, 80)
            }
            MetaRowView(title: "", items: current == .collection ? details.collectionParts : details.recommendations)
                .id(current)
        }
    }
}

/// Tab title: big and bold; dimmed when not selected, a soft tile when focused.
struct RelatedTabStyle: ButtonStyle {
    let isSelected: Bool

    func makeBody(configuration: Configuration) -> some View {
        TabLabel(configuration: configuration, isSelected: isSelected)
    }

    private struct TabLabel: View {
        let configuration: ButtonStyle.Configuration
        let isSelected: Bool
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .font(.title2.weight(.bold))
                .foregroundStyle(isFocused ? Color.black : Color.white.opacity(isSelected ? 1 : 0.5))
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(isFocused ? Color.white : Color.clear)
                )
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
                .animation(.easeOut(duration: 0.12), value: isFocused)
        }
    }
}
