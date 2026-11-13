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
                Picker("Type", selection: $kind) {
                    Text("Jellyfin").tag("jellyfin")
                    Text("Emby").tag("emby")
                }
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
    @State private var items: [MetaPreview] = []
    @State private var loaded = false

    var body: some View {
        Group {
            if !items.isEmpty {
                MetaRowView(title: "\(library.name) · \(library.server.name)", items: items)
            }
        }
        .task(id: library.key) {
            guard !loaded else { return }
            loaded = true
            items = (try? await app.core.mediaServers.latest(library: library, start: 0, limit: 30)) ?? []
        }
    }
}
