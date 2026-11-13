import SwiftUI
import NuvioShared

/// Local scraper plugins (Nuvio JS repositories), like Android's Plugins screen.
struct PluginsView: View {
    @EnvironmentObject private var app: AppModel
    @State private var revision = 0
    @State private var url = ""
    @State private var isWorking = false
    @State private var message: String?

    private var plugins: PluginService { app.core.plugins }

    var body: some View {
        List {
            Section {
                Toggle("Enable plugins", isOn: Binding(get: { plugins.pluginsEnabled }, set: { plugins.pluginsEnabled = $0; revision += 1 }))
                if !app.core.config.hasTmdb {
                    Text("Plugins look titles up by TMDB id; this build has no TMDB key.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("Add a repository") {
                TextField("Repository URL", text: $url)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button(isWorking ? "Working…" : "Add") {
                    run { _ = try await plugins.addRepository(input: url); url = "" }
                }
                .disabled(url.isEmpty || isWorking)
                if !plugins.repositories.isEmpty {
                    Button("Update all repositories") { run { try await plugins.refreshAll() } }.disabled(isWorking)
                }
            }
            ForEach(plugins.repositories, id: \.id) { repo in
                Section(repo.name) {
                    if let description = repo.description_ { Text(description).font(.caption).foregroundStyle(.secondary) }
                    ForEach(plugins.scrapers.filter { $0.repositoryId == repo.id }, id: \.id) { scraper in
                        Toggle(isOn: Binding(
                            get: { scraper.enabled },
                            set: { plugins.setScraperEnabled(id: scraper.id, enabled: $0); revision += 1 }
                        )) {
                            VStack(alignment: .leading) {
                                Text(scraper.name)
                                Text("v\(scraper.version) · \(scraper.supportedTypes.joined(separator: ", "))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Button("Remove repository", role: .destructive) {
                        plugins.removeRepository(id: repo.id)
                        revision += 1
                    }
                }
            }
            if let message { Section { Text(message).foregroundStyle(.secondary) } }
        }
        .id(revision)
        .navigationTitle("Plugins")
    }

    private func run(_ work: @escaping () async throws -> Void) {
        Task { @MainActor in
            isWorking = true
            defer { isWorking = false; revision += 1 }
            do {
                try await work()
                message = nil
            } catch {
                message = error.localizedDescription
            }
        }
    }
}
