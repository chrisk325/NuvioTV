import SwiftUI
import NuvioShared

/// Jellyfin / Emby servers: sign in, list, remove. Their libraries show on Home and their copies
/// of a title appear as streams.
struct MediaServersView: View {
    @EnvironmentObject private var app: AppModel
    @State private var revision = 0
    @State private var kind = "jellyfin"
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var isSigningIn = false
    @State private var message: String?

    var body: some View {
        List {
            Section("Servers") {
                if app.core.mediaServers.servers.isEmpty { Text("None yet").foregroundStyle(.secondary) }
                ForEach(app.core.mediaServers.servers, id: \.id) { server in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(server.name)
                            Text("\(server.kind.capitalized) · \(server.address)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Remove", role: .destructive) {
                            app.core.mediaServers.remove(serverId: server.id)
                            app.serversChanged()
                            revision += 1
                        }
                    }
                }
            }
            Section("Add a server") {
                ChoiceRow(title: "Server type", options: [("jellyfin", "Jellyfin"), ("emby", "Emby")], selection: $kind)
                TextField("Address (http://192.168.1.10:8096)", text: $address)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Username", text: $username).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Password", text: $password)
                Button(isSigningIn ? "Signing in…" : "Sign in") {
                    Task { @MainActor in
                        isSigningIn = true
                        defer { isSigningIn = false }
                        do {
                            _ = try await app.core.mediaServers.signIn(kind: kind, address: address, username: username, password: password)
                            address = ""; username = ""; password = ""
                            message = nil
                            app.serversChanged()
                            revision += 1
                        } catch {
                            message = error.localizedDescription
                        }
                    }
                }
                .disabled(address.isEmpty || username.isEmpty || isSigningIn)
            }
            if let message { Section { Text(message).foregroundStyle(.red) } }
        }
        .id(revision)
        .navigationTitle("Media servers")
    }
}

/// "Latest in <library>" row on Home.
struct ServerLibraryRow: View {
    @EnvironmentObject private var app: AppModel
    let library: ServerLibrary
    var onFocus: ((MetaPreview) -> Void)? = nil
    @State private var items: [MetaPreview] = []
    @State private var loaded = false

    var body: some View {
        Group {
            if !items.isEmpty {
                MetaRowView(title: title, items: items, seeAll: .serverLibrary(library), onFocus: onFocus)
            } else if !loaded {
                // Something on screen while loading: an empty row never appears, so its .task
                // never ran and Home stayed blank with only a media server set up.
                VStack(alignment: .leading) {
                    Text(title).font(.headline).padding(.horizontal, 60)
                    ProgressView().padding(60)
                }
            }
        }
        .task(id: library.key) {
            guard !loaded else { return }
            loaded = true
            items = (try? await app.core.mediaServers.latest(library: library, start: 0, limit: 30)) ?? []
        }
    }

    /// Like add-on rows: "Films - Movie"; the server name only when there are several servers.
    private var title: String {
        let base = "\(library.name) - \(library.typeLabel)"
        return app.core.mediaServers.servers.count > 1 ? "\(base) · \(library.server.name)" : base
    }
}

/// Whole Jellyfin / Emby library as a paged poster grid ("See all").
struct ServerLibraryGridView: View {
    @EnvironmentObject private var app: AppModel
    let library: ServerLibrary

    @State private var items: [MetaPreview] = []
    @State private var hasMore = true
    @State private var isLoading = false
    private let pageSize: Int32 = 60

    private var columns: [GridItem] { PosterGrid.columns(landscape: app.settings.modernLandscapePosters) }

    var body: some View {
        ScrollView {
            Text("\(library.name) - \(library.typeLabel)")
                .font(.title2.weight(.semibold))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 60)
                .padding(.top, 20)
            LazyVGrid(columns: columns, spacing: 50) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    PosterCard(item: item)
                        .onAppear {
                            if index >= items.count - 8 { Task { await loadMore() } }
                        }
                }
            }
            .padding(60)
            if isLoading { ProgressView().padding() }
            if !isLoading && items.isEmpty && !hasMore {
                MessageView(title: "Nothing in this library", systemImage: "film.stack")
            }
        }
        .task { if items.isEmpty { await loadMore() } }
    }

    @MainActor
    private func loadMore() async {
        guard hasMore, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        let page = (try? await app.core.mediaServers.latest(library: library, start: Int32(items.count), limit: pageSize)) ?? []
        let known = Set(items.map(\.id))
        let fresh = page.filter { !known.contains($0.id) }
        items += fresh
        hasMore = page.count >= Int(pageSize) && !fresh.isEmpty
    }
}
