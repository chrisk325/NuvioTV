import SwiftUI
import NuvioShared

struct DebridSettingsView: View {
    @EnvironmentObject private var app: AppModel
    @State private var revision = 0
    @State private var deviceCode: DebridDeviceCode?
    @State private var keyEntry: DebridProvider?
    @State private var keyText = ""
    @State private var error: String?

    private var debrid: DebridService { app.core.debrid }

    var body: some View {
        List {
            Section {
                Text("Debrid services turn torrent streams into direct links that play on Apple TV. Cached torrents start instantly.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            ForEach(DebridProviders.shared.all, id: \.id) { provider in
                Section(provider.displayName) {
                    if debrid.isConnected(providerId: provider.id) {
                        LabeledContent("Status", value: "Connected")
                        Button(debrid.preferredProviderId == provider.id ? "✓ Used for torrents" : "Use for torrents") {
                            debrid.preferredProviderId = provider.id
                            revision += 1
                        }
                        Button("Disconnect", role: .destructive) {
                            debrid.setApiKey(providerId: provider.id, key: "")
                            revision += 1
                        }
                    } else {
                        if provider.supportsDeviceCode {
                            Button("Sign in with code") { startDeviceCode(provider) }
                        }
                        Button("Enter API key") {
                            keyText = ""
                            keyEntry = provider
                        }
                    }
                }
            }
            Section {
                Toggle("Show uncached torrents", isOn: Binding(
                    get: { debrid.showUncached },
                    set: { debrid.showUncached = $0; revision += 1 }
                ))
            }
            if let error {
                Section { Text(error).foregroundStyle(.red) }
            }
        }
        .id(revision)
        .navigationTitle("Debrid")
        .sheet(item: Binding(get: { deviceCode.map(IdentifiedCode.init) }, set: { deviceCode = $0?.code })) { item in
            DeviceCodeSheet(
                title: "Sign in to \(DebridProviders.shared.byId(id: item.code.providerId)?.displayName ?? "debrid")",
                code: item.code.userCode,
                url: item.code.verificationUrl,
                intervalSeconds: Int(item.code.intervalSeconds),
                poll: { try await debrid.pollDeviceCode(code: item.code).boolValue }
            ) { _ in
                deviceCode = nil
                revision += 1
            }
        }
        .sheet(item: $keyEntry) { provider in
            VStack(alignment: .leading, spacing: 30) {
                Text("\(provider.displayName) API key").font(.title2).bold()
                SecureField("API key", text: $keyText)
                HStack(spacing: 30) {
                    Button("Save") {
                        debrid.setApiKey(providerId: provider.id, key: keyText)
                        keyEntry = nil
                        revision += 1
                    }
                    .disabled(keyText.trimmingCharacters(in: .whitespaces).isEmpty)
                    Button("Cancel", role: .cancel) { keyEntry = nil }
                }
            }
            .padding(80)
            .frame(maxWidth: 1200)
        }
    }

    private func startDeviceCode(_ provider: DebridProvider) {
        error = nil
        Task { @MainActor in
            do {
                deviceCode = try await debrid.startDeviceCode(providerId: provider.id)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

private struct IdentifiedCode: Identifiable {
    let code: DebridDeviceCode
    var id: String { code.deviceCode }
}

extension DebridProvider: Identifiable {}
