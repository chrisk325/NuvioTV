import SwiftUI
import NuvioShared

@MainActor
final class LiveTvModel: ObservableObject {
    @Published private(set) var state: LiveTvState?
    @Published var tick = Date()
    private var subscription: CoreSubscription?
    private var timer: Timer?

    func attach(_ core: NuvioCore) {
        guard subscription == nil else { return }
        subscription = core.observeLiveTv { [weak self] value in
            MainActor.assumeIsolated { self?.state = value }
        }
        // Refresh "now playing" once a minute.
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick = Date() }
        }
    }
}

struct LiveTvView: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var model = LiveTvModel()
    @State private var selectedGroup: String?
    @State private var playback: PlaybackRequest?
    @State private var showSources = false
    @State private var guideChannel: LiveTvChannel?
    @State private var error: String?

    var body: some View {
        Group {
            if app.core.liveTv.sources.isEmpty {
                VStack(spacing: 30) {
                    MessageView(
                        title: "Live TV",
                        message: "Add an M3U playlist, Xtream Codes account or Stalker portal to watch live channels with a programme guide.",
                        systemImage: "tv"
                    )
                    Button("Add a source") { showSources = true }
                }
            } else if let state = model.state, !state.groups.isEmpty {
                content(state)
            } else if model.state?.isLoading ?? true {
                LoadingView(label: "Loading channels…")
            } else {
                VStack(spacing: 30) {
                    MessageView(title: "No channels", message: model.state?.errors.joined(separator: "\n"))
                    Button("Sources") { showSources = true }
                }
            }
        }
        .task {
            model.attach(app.core)
            try? await app.core.loadLiveTv(force: false)
        }
        .sheet(isPresented: $showSources, onDismiss: {
            Task { try? await app.core.loadLiveTv(force: true) }
        }) {
            NavigationStack { LiveTvSourcesView().environmentObject(app) }
        }
        .sheet(item: Binding(get: { guideChannel.map(ChannelBox.init) }, set: { guideChannel = $0?.channel })) { box in
            ChannelGuideView(channel: box.channel) { request in
                guideChannel = nil
                playback = request
            }
            .environmentObject(app)
        }
        .fullScreenCover(item: $playback) { request in
            PlayerContainer(request: request).environmentObject(app)
        }
        .alert("Couldn't play channel", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(error ?? "") }
    }

    private func content(_ state: LiveTvState) -> some View {
        let groups = state.groups
        let current = groups.first { $0.name == selectedGroup } ?? groups.first!
        return HStack(alignment: .top, spacing: 0) {
            List {
                Section {
                    Button { showSources = true } label: { Label("Sources", systemImage: "slider.horizontal.3") }
                    Button { Task { try? await app.core.loadLiveTv(force: true) } } label: {
                        Label(state.isLoading ? "Refreshing…" : "Refresh", systemImage: "arrow.clockwise")
                    }
                }
                Section("\(state.channelCount) channels") {
                    ForEach(groups, id: \.name) { group in
                        Button {
                            selectedGroup = group.name
                        } label: {
                            HStack {
                                Text(group.name).lineLimit(1)
                                Spacer()
                                Text("\(group.channels.count)").foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                if !state.errors.isEmpty {
                    Section("Problems") {
                        ForEach(state.errors, id: \.self) { Text($0).font(.caption).foregroundStyle(.secondary) }
                    }
                }
            }
            .frame(width: 560)
            .focusSection()

            List {
                Section(current.name) {
                    ForEach(Array(current.channels.enumerated()), id: \.offset) { index, channel in
                        Button {
                            play(channel, in: current.channels, index: index)
                        } label: {
                            ChannelRow(channel: channel, now: app.core.liveTv.nowPlaying(channel: channel),
                                       next: app.core.liveTv.upNext(channel: channel),
                                       isFavorite: app.core.liveTv.isFavorite(channel: channel), tick: model.tick)
                        }
                        .contextMenu {
                            Button(app.core.liveTv.isFavorite(channel: channel) ? "Remove from favourites" : "Add to favourites") {
                                _ = app.core.liveTv.toggleFavorite(channel: channel)
                            }
                            Button("Programme guide") { guideChannel = channel }
                        }
                    }
                }
            }
            .focusSection()
        }
    }

    private func play(_ channel: LiveTvChannel, in list: [LiveTvChannel], index: Int) {
        Task { @MainActor in
            do {
                let result = try await app.core.liveTv.playback(channel: channel)
                var request = PlaybackRequest(
                    url: result.url,
                    headers: result.headers,
                    meta: app.core.liveTvMeta(channel: channel),
                    video: nil,
                    videoId: channel.id,
                    title: result.title,
                    startPositionMs: 0
                )
                request.isLive = true
                request.liveChannels = list
                request.liveIndex = index
                playback = request
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

private struct ChannelBox: Identifiable {
    let channel: LiveTvChannel
    var id: String { channel.id }
}

struct ChannelRow: View {
    let channel: LiveTvChannel
    let now: LiveTvProgramme?
    let next: LiveTvProgramme?
    let isFavorite: Bool
    let tick: Date

    var body: some View {
        HStack(spacing: 24) {
            RemoteImage(url: channel.logoUrl, contentMode: .fit)
                .frame(width: 120, height: 68)
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(channel.name).font(.headline).lineLimit(1)
                    if isFavorite { Image(systemName: "star.fill").foregroundStyle(.yellow).font(.caption) }
                    if channel.hasCatchup { Image(systemName: "clock.arrow.circlepath").font(.caption).foregroundStyle(.secondary) }
                }
                if let now {
                    Text("Now: \(now.title)").font(.caption).lineLimit(1)
                    ProgressView(value: now.progress(nowMs: Int64(tick.timeIntervalSince1970 * 1000)))
                        .frame(maxWidth: 400)
                }
                if let next {
                    Text("Next \(LiveTvFormat.time(next.startEpochMs)): \(next.title)")
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
    }
}

enum LiveTvFormat {
    static func time(_ epochMs: Int64) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        formatter.dateStyle = .none
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000))
    }
}

/// One channel's guide: past programmes play from catch-up when the provider keeps an archive.
struct ChannelGuideView: View {
    @EnvironmentObject private var app: AppModel
    let channel: LiveTvChannel
    let onPlay: (PlaybackRequest) -> Void

    var body: some View {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let programmes = app.core.liveTv.programmes(channel: channel)
        List {
            Section(channel.name) {
                if programmes.isEmpty { Text("No guide data for this channel").foregroundStyle(.secondary) }
                ForEach(Array(programmes.enumerated()), id: \.offset) { _, programme in
                    let isPast = programme.stopEpochMs < now
                    Button {
                        if isPast, let catchup = app.core.liveTv.catchupPlayback(channel: channel, programme: programme) {
                            var request = PlaybackRequest(url: catchup.url, headers: catchup.headers,
                                                          meta: app.core.liveTvMeta(channel: channel), video: nil,
                                                          videoId: channel.id, title: catchup.title, startPositionMs: 0)
                            request.isLive = true
                            onPlay(request)
                        }
                    } label: {
                        HStack(alignment: .top, spacing: 24) {
                            Text("\(LiveTvFormat.time(programme.startEpochMs))–\(LiveTvFormat.time(programme.stopEpochMs))")
                                .font(.caption.monospacedDigit())
                                .frame(width: 260, alignment: .leading)
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(programme.title).font(.headline)
                                    if programme.isOnAir(nowMs: now) { Text("ON NOW").font(.caption2.bold()).foregroundStyle(.red) }
                                    if isPast && channel.hasCatchup { Image(systemName: "play.circle").foregroundStyle(.secondary) }
                                }
                                if let description = programme.description_ {
                                    Text(description).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                }
                            }
                        }
                        .opacity(isPast && !channel.hasCatchup ? 0.5 : 1)
                    }
                }
            }
        }
        .frame(minWidth: 1400)
    }
}

/// Add / remove M3U, Xtream and Stalker sources.
struct LiveTvSourcesView: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var revision = 0
    @State private var type = "m3u"
    @State private var name = ""
    @State private var url = ""
    @State private var username = ""
    @State private var password = ""
    @State private var mac = ""
    @State private var epg = ""

    var body: some View {
        List {
            Section("Sources") {
                if app.core.liveTv.sources.isEmpty { Text("None yet").foregroundStyle(.secondary) }
                ForEach(app.core.liveTv.sources, id: \.id) { source in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(source.name)
                            Text("\(source.type.uppercased()) · \(source.url)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Button("Remove", role: .destructive) {
                            app.core.liveTv.removeSource(id: source.id)
                            revision += 1
                        }
                    }
                }
            }
            Section("Add a source") {
                Picker("Type", selection: $type) {
                    Text("M3U playlist").tag("m3u")
                    Text("Xtream Codes").tag("xtream")
                    Text("Stalker portal").tag("stalker")
                }
                TextField("Name (optional)", text: $name)
                TextField(type == "m3u" ? "Playlist URL" : type == "xtream" ? "Server URL (http://host:port)" : "Portal URL", text: $url)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                if type == "xtream" || type == "stalker" {
                    TextField(type == "stalker" ? "Username (optional)" : "Username", text: $username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField(type == "stalker" ? "Password (optional)" : "Password", text: $password)
                }
                if type == "stalker" {
                    TextField("MAC address (00:1A:79:…)", text: $mac).textInputAutocapitalization(.characters).autocorrectionDisabled()
                }
                TextField("XMLTV guide URL (optional)", text: $epg)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Add") {
                    _ = app.core.liveTv.addSource(type: type, name: name, url: url, username: username,
                                                  password: password, macAddress: mac, epgUrl: epg)
                    name = ""; url = ""; username = ""; password = ""; mac = ""; epg = ""
                    revision += 1
                }
                .disabled(url.isEmpty || (type == "xtream" && (username.isEmpty || password.isEmpty)) || (type == "stalker" && mac.isEmpty))
            }
            Section { Button("Done") { dismiss() } }
        }
        .id(revision)
        .navigationTitle("Live TV sources")
    }
}
