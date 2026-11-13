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
                            StreamRow(stream: stream)
                        }
                    }
                }
            }
        }
    }

    /// Directly playable streams first, then by quality.
    private func sorted(_ streams: [AddonStream]) -> [AddonStream] {
        streams.enumerated().sorted { a, b in
            if a.element.isDirectlyPlayable != b.element.isDirectlyPlayable { return a.element.isDirectlyPlayable }
            return a.offset < b.offset
        }.map(\.element)
    }

    private func select(_ stream: AddonStream) {
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

    private func unplayableReason(_ stream: AddonStream) -> String {
        if stream.isTorrent() {
            return "This is a torrent stream. The Apple TV app has no torrent engine yet; use a debrid add-on that returns HTTP links."
        }
        if stream.isYouTube() {
            return "YouTube streams aren't supported in the Apple TV app yet."
        }
        return "This stream doesn't provide a direct video URL."
    }
}

struct StreamRow: View {
    let stream: AddonStream

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
            if stream.isTorrent() {
                Image(systemName: "arrow.down.circle").foregroundStyle(.secondary)
            } else if !stream.isDirectlyPlayable {
                Image(systemName: "nosign").foregroundStyle(.secondary)
            }
        }
        .opacity(stream.isDirectlyPlayable ? 1 : 0.5)
    }
}
