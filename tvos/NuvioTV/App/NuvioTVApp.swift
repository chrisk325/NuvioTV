import SwiftUI

@main
struct NuvioTVApp: App {
    @StateObject private var app = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .task { await app.start() }
        }
    }
}
