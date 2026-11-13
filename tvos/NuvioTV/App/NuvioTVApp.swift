import SwiftUI
import NuvioShared

@main
struct NuvioTVApp: App {
    @StateObject private var session = SessionModel()

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
