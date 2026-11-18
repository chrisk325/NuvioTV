import SwiftUI
import NuvioShared

/// Trakt, Simkl, MDBList and AniList sign-in (device code / PIN / token), like Android's tracking settings.
struct AccountsView: View {
    @EnvironmentObject private var app: AppModel
    @State private var revision = 0
    @State private var signIn: SignInPrompt?
    @State private var aniListToken = ""
    @State private var showAniList = false
    @State private var message: String?
    @State private var isSyncing = false
    @State private var cloudLogin: CloudLoginBox?
    @State private var showEmail = false
    @State private var email = ""
    @State private var password = ""
    @State private var emailError: String?
    @State private var isSigningIn = false

    private var core: NuvioCore { app.core }

    var body: some View {
        List {
            Section("Nuvio account") {
                if !core.cloud.isConfigured {
                    Text("This build has no Nuvio account configuration.").foregroundStyle(.secondary)
                } else if core.cloud.isSignedIn {
                    LabeledContent("Signed in", value: core.cloud.email ?? "Nuvio")
                    Button(isSyncing ? "Syncing…" : "Sync now") {
                        Task { @MainActor in
                            isSyncing = true
                            await app.syncCloud()
                            await app.refreshAddons()
                            isSyncing = false
                            message = app.cloudSyncError ?? "Add-ons, library, progress and watched history synced."
                        }
                    }
                    .disabled(isSyncing)
                    Button("Sign out", role: .destructive) { core.cloud.signOut(); revision += 1 }
                } else {
                    Button("Sign in with QR code") {
                        message = nil
                        Task { @MainActor in
                            do {
                                cloudLogin = CloudLoginBox(login: try await core.cloud.startTvLogin(deviceName: "Apple TV"))
                            } catch {
                                message = error.localizedDescription
                            }
                        }
                    }
                    Button("Sign in with email") { emailError = nil; showEmail = true }
                }
            }
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
            Section("MDBList") {
                if !core.mdbListTracker.isConfigured {
                    Text("This build has no MDBList client id.").foregroundStyle(.secondary)
                } else if core.mdbListTracker.isConnected {
                    LabeledContent("Signed in", value: core.mdbListTracker.username ?? "MDBList")
                    Button(isSyncing ? "Syncing…" : "Sync now") {
                        Task { @MainActor in
                            isSyncing = true
                            await app.syncTrackers()
                            isSyncing = false
                            message = app.trackerSyncError ?? "MDBList history, progress and watchlist synced."
                        }
                    }
                    .disabled(isSyncing)
                    NavigationLink("Lists shown in Library") { MdbListVisibilityView() }
                    Button("Sign out", role: .destructive) {
                        Task { @MainActor in
                            try? await core.mdbListTracker.signOut()
                            revision += 1
                            await app.syncTrackers()
                        }
                    }
                } else {
                    Button("Sign in with MDBList") {
                        start { try await core.mdbListTracker.startSignIn() } poll: { try await core.mdbListTracker.pollSignIn(signIn: $0).boolValue }
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
                title: "Sign in to \(Self.providerName(prompt.signIn.providerId))",
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
        .sheet(item: $cloudLogin) { box in
            DeviceCodeSheet(
                title: "Sign in to Nuvio",
                code: box.login.code,
                url: box.login.webUrl,
                intervalSeconds: Int(box.login.pollIntervalSeconds),
                poll: { try await core.cloud.pollTvLogin(login: box.login).boolValue }
            ) { success in
                cloudLogin = nil
                revision += 1
                if success {
                    Task { @MainActor in
                        await app.syncCloud()
                        await app.refreshAddons()
                    }
                }
            }
        }
        .sheet(isPresented: $showEmail) {
            VStack(alignment: .leading, spacing: 24) {
                Text("Sign in to Nuvio").font(.title2).bold()
                TextField("Email", text: $email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                if let emailError {
                    Text(emailError).foregroundStyle(.red)
                }
                HStack(spacing: 30) {
                    Button(isSigningIn ? "Signing in…" : "Sign in") {
                        Task { @MainActor in
                            isSigningIn = true
                            emailError = nil
                            do {
                                try await core.cloud.signInWithEmail(email: email, password: password)
                                isSigningIn = false
                                showEmail = false
                                password = ""
                                message = "Signed in. Syncing add-ons, library and progress…"
                                revision += 1
                                await app.syncCloud()
                                await app.refreshAddons()
                                message = app.cloudSyncError ?? "Signed in and synced."
                            } catch {
                                // Keep the form open with the reason (wrong password, …).
                                isSigningIn = false
                                emailError = error.localizedDescription
                            }
                        }
                    }
                    .disabled(email.isEmpty || password.isEmpty || isSigningIn)
                    Button("Cancel", role: .cancel) { showEmail = false }
                }
            }
            .padding(80)
            .frame(maxWidth: 1200)
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

    private static func providerName(_ id: String) -> String {
        switch id {
        case "mdblist": return "MDBList"
        case "anilist": return "AniList"
        default: return id.capitalized
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

private struct CloudLoginBox: Identifiable {
    let login: CloudTvLogin
    var id: String { login.code }
}

private struct SignInPrompt: Identifiable {
    let signIn: DeviceSignIn
    let poll: (DeviceSignIn) async throws -> Bool
    var id: String { signIn.deviceCode }
}
