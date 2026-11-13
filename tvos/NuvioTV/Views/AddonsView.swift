import SwiftUI
import NuvioShared

struct AddonsView: View {
    @EnvironmentObject private var app: AppModel
    @State private var showInstall = false
    @State private var selected: Addon?

    var body: some View {
        List {
            Section {
                Button {
                    showInstall = true
                } label: {
                    Label("Install add-on", systemImage: "plus")
                }
                Button {
                    Task { await app.refreshAddons() }
                } label: {
                    HStack {
                        Label("Refresh manifests", systemImage: "arrow.clockwise")
                        if app.isRefreshingAddons {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
            }

            if !app.failedAddonUrls.isEmpty {
                Section("Couldn't reach") {
                    ForEach(app.failedAddonUrls, id: \.self) { url in
                        Text(url).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }

            Section("Installed (priority order)") {
                ForEach(app.addons, id: \.baseUrl) { addon in
                    Button {
                        selected = addon
                    } label: {
                        AddonRow(addon: addon)
                    }
                }
            }
        }
        .navigationTitle("Add-ons")
        .sheet(isPresented: $showInstall) {
            InstallAddonView().environmentObject(app)
        }
        .confirmationDialog(
            selected?.displayName ?? "",
            isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }),
            presenting: selected
        ) { addon in
            Button(addon.enabled ? "Disable" : "Enable") {
                Task { await app.setEnabled(addon, enabled: !addon.enabled) }
            }
            Button("Move up") { Task { await app.move(addon, by: -1) } }
            Button("Move down") { Task { await app.move(addon, by: 1) } }
            Button("Remove", role: .destructive) { Task { await app.remove(addon) } }
            Button("Cancel", role: .cancel) {}
        } message: { addon in
            Text(addon.baseUrl)
        }
    }
}

struct AddonRow: View {
    let addon: Addon

    var body: some View {
        HStack(spacing: 24) {
            RemoteImage(url: addon.logo, contentMode: .fit)
                .frame(width: 80, height: 80)
                .cornerRadius(12)
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(addon.displayName).font(.headline)
                    Text("v\(addon.version)").font(.caption).foregroundStyle(.secondary)
                }
                Text(capabilities).font(.caption).foregroundStyle(.secondary)
                if let description = addon.description_, !description.isEmpty {
                    Text(description).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            Spacer()
            if !addon.enabled {
                Text("Disabled").font(.caption).foregroundStyle(.secondary)
            }
        }
        .opacity(addon.enabled ? 1 : 0.5)
    }

    private var capabilities: String {
        var parts = addon.resources.map(\.name)
        if !addon.catalogs.isEmpty { parts.append("\(addon.catalogs.count) catalogs") }
        return parts.joined(separator: " · ")
    }
}

struct InstallAddonView: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var isInstalling = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            Text("Install add-on").font(.title2).bold()
            Text("Enter the add-on's manifest URL (https://…/manifest.json). stremio:// links work too.")
                .foregroundStyle(.secondary)
            TextField("https://example.com/manifest.json", text: $url)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            if let error {
                Text(error).foregroundStyle(.red)
            }
            HStack(spacing: 30) {
                Button {
                    Task { await install() }
                } label: {
                    if isInstalling { ProgressView() } else { Text("Install") }
                }
                .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty || isInstalling)
                Button("Cancel", role: .cancel) { dismiss() }
            }
        }
        .padding(80)
        .frame(maxWidth: 1400)
    }

    @MainActor
    private func install() async {
        isInstalling = true
        error = nil
        defer { isInstalling = false }
        do {
            try await app.install(url: url)
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
