import SwiftUI
import NuvioShared

struct StreamsView: View {
    @EnvironmentObject private var app: AppModel
    let target: StreamTarget

    @State private var update: StreamsUpdate?
    @State private var subscription: CoreSubscription?
    @State private var playback: PlaybackRequest?
    @State private var showUnplayable: AddonStream?
    @State private var autoPlayed = false
    @State private var resolving: AddonStream?
    @State private var resolveError: String?

    var body: some View {
        ZStack {
            // The title's artwork behind everything, dimmed toward the stream cards.
            FullScreenBackdrop(url: target.meta.backdropUrl) {
                LinearGradient(colors: [.black.opacity(0.85), .black.opacity(0.55), .black.opacity(0.75)],
                               startPoint: .leading, endPoint: .trailing)
            }

            HStack(alignment: .top, spacing: 60) {
                header
                    .frame(width: 520, alignment: .topLeading)
                    .padding(.top, 40)

                Group {
                    if let update, !update.groups.isEmpty {
                        list(update)
                    } else if let update, update.isComplete {
                        MessageView(
                            title: "No streams found",
                            message: update.failedAddons.isEmpty
                                ? "None of your add-ons returned streams for this title."
                                : "Failed: \(update.failedAddons.joined(separator: ", "))",
                            systemImage: "play.slash"
                        )
                    } else {
                        LoadingView(label: "Finding streams…")
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            .padding(.horizontal, 80)
        }
        .onAppear(perform: start)
        .onDisappear {
            subscription?.cancel()
            subscription = nil
        }
        .fullScreenCover(item: $playback) { request in
            PlayerContainer(request: request)
                .environmentObject(app)
        }
        .alert(
            "Debrid couldn't play this stream",
            isPresented: Binding(get: { resolveError != nil }, set: { if !$0 { resolveError = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(resolveError ?? "")
        }
        .overlay {
            if resolving != nil {
                ZStack {
                    Color.black.opacity(0.6).ignoresSafeArea()
                    LoadingView(label: "Resolving with \(app.core.debrid.preferredProviderId.isEmpty ? "debrid" : app.core.debrid.preferredProviderId)…")
                }
            }
        }
        .alert(
            "Can't play this stream on Apple TV",
            isPresented: Binding(get: { showUnplayable != nil }, set: { if !$0 { showUnplayable = nil } }),
            presenting: showUnplayable
        ) { _ in
            Button("OK", role: .cancel) {}
        } message: { stream in
            Text(unplayableReason(stream))
        }
    }

    private func start() {
        guard subscription == nil else { return }
        let embedded = target.video?.streams ?? []
        subscription = app.core.loadStreams(
            type: target.meta.apiType,
            videoId: target.videoId,
            embedded: embedded
        ) { value in
            MainActor.assumeIsolated {
                update = value
                autoPlayIfMatching(value)
            }
        }
    }

    /// Settings ▸ Player ▸ auto-play regex: start the first matching playable stream.
    private func autoPlayIfMatching(_ value: StreamsUpdate) {
        let regex = app.settings.streamAutoPlayRegex
        guard !autoPlayed, playback == nil, !regex.isEmpty else { return }
        let match = value.groups.flatMap(\.streams).first {
            $0.isDirectlyPlayable && StreamAutoPicker.matchesRegex($0, regex: regex)
        }
        guard let match else { return }
        autoPlayed = true
        select(match)
    }

    private var movieInfo: String {
        [target.meta.releaseInfo, target.meta.runtime].compactMap { $0 }.joined(separator: "  ·  ")
    }

    /// Logo / title, episode and search progress on the left.
    private var header: some View {
        VStack(alignment: .leading, spacing: 20) {
            if let logo = target.meta.logo {
                RemoteImage(url: logo, contentMode: .fit)
                    .frame(maxWidth: 460, maxHeight: 160, alignment: .leading)
            } else {
                Text(target.meta.name).font(.system(size: 48, weight: .bold)).lineLimit(3)
            }
            if let video = target.video {
                VStack(alignment: .leading, spacing: 6) {
                    if let label = video.episodeLabel {
                        Text(label).font(.headline).foregroundStyle(.secondary)
                    }
                    Text(video.title).font(.title3).bold().lineLimit(2)
                    if let overview = video.overview, !overview.isEmpty {
                        Text(overview).font(.callout).foregroundStyle(.secondary).lineLimit(5)
                    }
                }
            } else if !movieInfo.isEmpty {
                Text(movieInfo).font(.callout).foregroundStyle(.secondary)
            }
            if let update {
                let count = update.groups.reduce(0) { $0 + $1.streams.count }
                HStack(spacing: 14) {
                    if !update.isComplete { ProgressView() }
                    Text(update.isComplete ? "\(count) streams" : "\(count) streams · searching…")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func list(_ update: StreamsUpdate) -> some View {
        ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: 22) {
                ForEach(Array(update.groups.enumerated()), id: \.offset) { _, group in
                    HStack(spacing: 14) {
                        if let logo = group.addonLogo {
                            RemoteImage(url: logo, contentMode: .fit).frame(width: 40, height: 40)
                        }
                        Text(group.addonName).font(.headline)
                        Text("\(group.streams.count)").font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(.top, 20)
                    ForEach(Array(sorted(group.streams).enumerated()), id: \.offset) { _, stream in
                        Button {
                            select(stream)
                        } label: {
                            StreamRow(stream: stream, debridEnabled: app.core.debrid.hasAnyProvider)
                        }
                        .buttonStyle(StreamCardStyle())
                    }
                }
            }
            .padding(.vertical, 40)
            .padding(.horizontal, 20) // room for the focus scale
        }
    }

    /// Directly playable streams first, then by quality.
    private func sorted(_ streams: [AddonStream]) -> [AddonStream] {
        let debrid = app.core.debrid.hasAnyProvider
        func playable(_ s: AddonStream) -> Bool {
            s.isDirectlyPlayable || (debrid && s.needsDebridResolve && s.debridCache?.isCached != false)
        }
        return streams.enumerated()
            .filter { debrid ? (app.core.debrid.showUncached || $0.element.debridCache?.isCached != false) : true }
            .sorted { a, b in
                if playable(a.element) != playable(b.element) { return playable(a.element) }
                return a.offset < b.offset
            }
            .map(\.element)
    }

    private func select(_ stream: AddonStream) {
        if stream.needsDebridResolve && app.core.debrid.hasAnyProvider {
            resolveWithDebrid(stream)
            return
        }
        guard let url = stream.directPlaybackUrl else {
            if stream.isExternal(), let external = stream.externalUrl, let link = URL(string: external) {
                // tvOS has no browser; try handing the link to whichever app claims it.
                UIApplication.shared.open(link)
                return
            }
            showUnplayable = stream
            return
        }
        _ = url
        playback = PlaybackRequest.make(
            stream: stream,
            target: target,
            resume: app.core.progressFor(videoId: target.videoId)
        )
    }

    private func resolveWithDebrid(_ stream: AddonStream) {
        guard resolving == nil else { return }
        resolving = stream
        Task { @MainActor in
            defer { resolving = nil }
            do {
                let playable = try await app.core.debrid.resolveToPlayable(
                    stream: stream,
                    season: target.video?.season?.int32Value ?? 0,
                    episode: target.video?.episode?.int32Value ?? 0
                )
                playback = PlaybackRequest.make(
                    stream: playable,
                    target: target,
                    resume: app.core.progressFor(videoId: target.videoId)
                )
            } catch {
                resolveError = error.localizedDescription
            }
        }
    }

    private func unplayableReason(_ stream: AddonStream) -> String {
        if stream.isTorrent() || stream.needsDebridResolve {
            return "This is a torrent stream. Connect Torbox, Premiumize or Real-Debrid in Settings ▸ Debrid to play it."
        }
        if stream.isYouTube() {
            return "YouTube streams aren't supported in the Apple TV app yet."
        }
        return "This stream doesn't provide a direct video URL."
    }
}

/// One stream like Stremio lays it out: the add-on's name column on the left and the full
/// description on the right, every line kept, so formatter layouts (AIOStreams etc.) show in full.
struct StreamRow: View {
    let stream: AddonStream
    var debridEnabled = false

    private var isPlayable: Bool {
        stream.isDirectlyPlayable || (debridEnabled && stream.needsDebridResolve)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 28) {
            Text(stream.getDisplayName())
                .font(.headline)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(width: 260, alignment: .leading)
            VStack(alignment: .leading, spacing: 10) {
                if let detail = stream.getDisplayDescription(), detail != stream.getDisplayName() {
                    Text(detail)
                        .font(.callout)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                badges
            }
        }
        .opacity(isPlayable ? 1 : 0.55)
    }

    @ViewBuilder
    private var badges: some View {
        if let cache = stream.debridCache {
            Text(cache.isCached ? "⚡ Cached on \(cache.providerName)" : "\(cache.providerName) · not cached")
                .font(.caption)
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background((cache.isCached ? Color.green : Color.gray).opacity(0.4), in: Capsule())
        } else if stream.needsDebridResolve {
            Label(debridEnabled ? "Torrent · via debrid" : "Torrent", systemImage: debridEnabled ? "bolt.circle" : "arrow.down.circle")
                .font(.caption).foregroundStyle(.secondary)
        } else if !stream.isDirectlyPlayable {
            Label("Not playable on Apple TV", systemImage: "nosign").font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Frosted glass card that lifts and brightens when focused.
struct StreamCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        StreamCard(configuration: configuration)
    }

    private struct StreamCard: View {
        let configuration: ButtonStyle.Configuration
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .foregroundStyle(.white)
                .padding(.horizontal, 30)
                .padding(.vertical, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(.ultraThinMaterial)
                        .overlay(
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .fill(Color.white.opacity(isFocused ? 0.18 : 0.02))
                        )
                }
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .strokeBorder(Color.white.opacity(isFocused ? 0.85 : 0.12), lineWidth: isFocused ? 3 : 1)
                )
                .scaleEffect(configuration.isPressed ? 0.98 : (isFocused ? 1.02 : 1))
                .shadow(color: .black.opacity(isFocused ? 0.5 : 0), radius: 24, y: 10)
                .animation(.easeOut(duration: 0.15), value: isFocused)
        }
    }
}
