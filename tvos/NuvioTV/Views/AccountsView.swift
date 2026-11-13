import SwiftUI
import NuvioShared

/// Trakt, Simkl and AniList sign-in (device code / PIN / token), like Android's tracking settings.
struct AccountsView: View {
    @EnvironmentObject private var app: AppModel
    @State private var revision = 0
    @State private var signIn: SignInPrompt?
    @State private var aniListToken = ""
    @State private var showAniList = false
    @State private var message: String?
    @State private var isSyncing = false

    private var core: NuvioCore { app.core }

    var body: some View {
        List {
            Section("Trakt") {
                if !core.trakt.isConfigured {
                    Text("This build has no Trakt client id.").foregroundStyle(.secondary)
                } else if core.trakt.isConnected {
                    LabeledContent("Signed in", value: core.trakt.username ?? "Trakt")
                    Button(isSyncing ? "Syncing…" : "Sync now") {
                        Task { @MainActor in
                            isSyncing = true
                            await app.syncTrackers()
                            isSyncing = false
                            message = app.trackerSyncError ?? "Trakt history, progress and watchlist synced."
                        }
                    }
                    .disabled(isSyncing)
                    Button("Sign out", role: .destructive) { core.trakt.signOut(); revision += 1 }
                } else {
                    Button("Sign in with Trakt") {
                        start { try await core.trakt.startSignIn() } poll: { try await core.trakt.pollSignIn(signIn: $0).boolValue }
                    }
                }
            }
            Section("Simkl") {
                if !core.simkl.isConfigured {
                    Text("This build has no Simkl client id.").foregroundStyle(.secondary)
                } else if core.simkl.isConnected {
                    LabeledContent("Signed in", value: core.simkl.username ?? "Simkl")
                    Button("Sign out", role: .destructive) { core.simkl.signOut(); revision += 1 }
                } else {
                    Button("Sign in with Simkl") {
                        start { try await core.simkl.startSignIn() } poll: { try await core.simkl.pollSignIn(signIn: $0).boolValue }
                    }
                }
            }
            Section("AniList") {
                if !core.aniList.isConfigured {
                    Text("This build has no AniList client id.").foregroundStyle(.secondary)
                } else if core.aniList.isConnected {
                    LabeledContent("Signed in", value: core.aniList.username ?? "AniList")
                    Button("Sign out", role: .destructive) { core.aniList.signOut(); revision += 1 }
                } else {
                    Button("Sign in with AniList") { showAniList = true }
                }
            }
            if let message {
                Section { Text(message).foregroundStyle(.secondary) }
            }
        }
        .id(revision)
        .navigationTitle("Accounts")
        .sheet(item: $signIn) { prompt in
            DeviceCodeSheet(
                title: "Sign in to \(prompt.signIn.providerId.capitalized)",
                code: prompt.signIn.userCode,
                url: prompt.signIn.verificationUrl,
                intervalSeconds: Int(prompt.signIn.intervalSeconds),
                poll: { try await prompt.poll(prompt.signIn) }
            ) { success in
                signIn = nil
                revision += 1
                if success { Task { await app.syncTrackers() } }
            }
        }
        .sheet(isPresented: $showAniList) {
            HStack(spacing: 60) {
                QRCodeView(text: core.aniList.authorizeUrl)
                VStack(alignment: .leading, spacing: 24) {
                    Text("Sign in to AniList").font(.title2).bold()
                    Text("Scan the code, approve Nuvio, then paste the token shown by AniList (your iPhone's keyboard can paste into Apple TV).")
                        .foregroundStyle(.secondary)
                    SecureField("Token", text: $aniListToken)
                    HStack(spacing: 30) {
                        Button("Connect") {
                            Task { @MainActor in
                                do {
                                    try await core.aniList.signIn(token: aniListToken)
                                    showAniList = false
                                    revision += 1
                                } catch {
                                    message = error.localizedDescription
                                }
                            }
                        }
                        Button("Cancel", role: .cancel) { showAniList = false }
                    }
                }
            }
            .padding(80)
        }
    }

    private func start(_ begin: @escaping () async throws -> DeviceSignIn, poll: @escaping (DeviceSignIn) async throws -> Bool) {
        message = nil
        Task { @MainActor in
            do {
                signIn = SignInPrompt(signIn: try await begin(), poll: poll)
            } catch {
                message = error.localizedDescription
            }
        }
    }
}

private struct SignInPrompt: Identifiable {
    let signIn: DeviceSignIn
    let poll: (DeviceSignIn) async throws -> Bool
    var id: String { signIn.deviceCode }
}
