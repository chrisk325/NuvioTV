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
        .navigationTitle(target.title)
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

    @ViewBuilder
    private func list(_ update: StreamsUpdate) -> some View {
        List {
            if !update.isComplete {
                HStack(spacing: 20) {
                    ProgressView()
                    Text("Still searching other add-ons…").foregroundStyle(.secondary)
                }
            }
            ForEach(Array(update.groups.enumerated()), id: \.offset) { _, group in
                Section(group.addonName) {
                    ForEach(Array(sorted(group.streams).enumerated()), id: \.offset) { _, stream in
                        Button {
                            select(stream)
                        } label: {
                            StreamRow(stream: stream, debridEnabled: app.core.debrid.hasAnyProvider)
                        }
                    }
                }
            }
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

struct StreamRow: View {
    let stream: AddonStream
    var debridEnabled = false

    private var isPlayable: Bool {
        stream.isDirectlyPlayable || (debridEnabled && stream.needsDebridResolve)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(alignment: .leading, spacing: 6) {
                Text(stream.getDisplayName())
                    .font(.headline)
                    .lineLimit(2)
                if let detail = stream.getDisplayDescription(), detail != stream.getDisplayName() {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(4)
                }
            }
            Spacer()
            if let cache = stream.debridCache {
                Text(cache.isCached ? "⚡ \(cache.providerName)" : "\(cache.providerName) · not cached")
                    .font(.caption)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .background((cache.isCached ? Color.green : Color.gray).opacity(0.35), in: Capsule())
            } else if stream.needsDebridResolve {
                Image(systemName: debridEnabled ? "bolt.circle" : "arrow.down.circle").foregroundStyle(.secondary)
            } else if !stream.isDirectlyPlayable {
                Image(systemName: "nosign").foregroundStyle(.secondary)
            }
        }
        .opacity(isPlayable ? 1 : 0.5)
    }
}
