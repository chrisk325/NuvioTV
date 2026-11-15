import SwiftUI
import NuvioShared

@main
struct NuvioTVApp: App {
    @StateObject private var session = SessionModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var lastForegroundSync = Date()

    var body: some Scene {
        WindowGroup {
            Group {
                if session.isPickingProfile {
                    ProfilePickerView()
                } else {
                    RootView()
                        .id(session.app.core.profileIndex)
                        .environmentObject(session.app)
                        .task(id: session.app.core.profileIndex) { await session.app.start() }
                }
            }
            .environmentObject(session)
            // nuviotv:// callbacks from Infuse / VLC (x-success …) bring Nuvio back to the front,
            // where the details / streams page is still open; Infuse's reported position is saved.
            .onOpenURL { url in ExternalPlayback.handle(url, core: session.app.core) }
            // Back in the foreground (e.g. after watching elsewhere): pull trackers and Jellyfin /
            // Emby progress again, at most every five minutes.
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active, !session.isPickingProfile,
                      Date().timeIntervalSince(lastForegroundSync) > 300 else { return }
                lastForegroundSync = Date()
                Task { await session.app.syncTrackers() }
            }
        }
    }
}

/// Holds the active profile's AppModel; switching profile builds a fresh core on that profile's storage.
@MainActor
final class SessionModel: ObservableObject {
    @Published private(set) var app: AppModel
    @Published var isPickingProfile: Bool

    init() {
        let app = AppModel()
        self.app = app
        isPickingProfile = app.core.profiles.shouldAskOnLaunch
    }

    var profiles: [UserProfile] { app.core.profiles.profiles }

    func switchTo(_ profile: UserProfile) {
        if profile.index != app.core.profileIndex {
            app.shutdown()
            app = AppModel(profileIndex: profile.index)
        }
        isPickingProfile = false
    }
}
