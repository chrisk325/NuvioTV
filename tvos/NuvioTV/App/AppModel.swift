import Foundation
import NuvioShared

/// Kotlin's `Stream` collides with Foundation's `Stream` (NSStream).
typealias AddonStream = NuvioShared.Stream

/// App-wide state backed by the shared Kotlin core. Kotlin callbacks are delivered on the
/// main thread (Dispatchers.Main), and Kotlin suspend functions must be called from it,
/// so everything here is main-actor isolated.
@MainActor
final class AppModel: ObservableObject {
    let core: NuvioCore

    @Published private(set) var addons: [Addon] = []
    @Published private(set) var continueWatching: [WatchProgress] = []
    @Published private(set) var library: [SavedItem] = []
    @Published private(set) var isRefreshingAddons = false
    @Published private(set) var failedAddonUrls: [String] = []

    private var subscriptions: [CoreSubscription] = []

    init() {
        core = NuvioCoreFactoryKt.createNuvioCore()
        subscriptions.append(core.observeAddons { [weak self] list in
            MainActor.assumeIsolated { self?.addons = list }
        })
        subscriptions.append(core.observeContinueWatching { [weak self] list in
            MainActor.assumeIsolated { self?.continueWatching = list }
        })
        subscriptions.append(core.observeLibrary { [weak self] list in
            MainActor.assumeIsolated { self?.library = list }
        })
    }

    func start() async {
        await refreshAddons()
    }

    func refreshAddons() async {
        isRefreshingAddons = true
        defer { isRefreshingAddons = false }
        do {
            failedAddonUrls = try await core.refreshAddons()
        } catch {
            failedAddonUrls = core.addonRepository.installedUrls
        }
    }

    /// Catalog rows for the home screen, in add-on priority order.
    var homeSlots: [CatalogSlot] {
        // `addons` is read so SwiftUI recomputes when the add-on list changes.
        _ = addons
        return core.homeSlots()
    }

    func install(url: String) async throws {
        _ = try await core.installAddon(url: url)
    }

    func remove(_ addon: Addon) async {
        try? await core.removeAddon(baseUrl: addon.baseUrl)
    }

    func setEnabled(_ addon: Addon, enabled: Bool) async {
        try? await core.setAddonEnabled(baseUrl: addon.baseUrl, enabled: enabled)
    }

    func move(_ addon: Addon, by offset: Int) async {
        try? await core.moveAddon(baseUrl: addon.baseUrl, offset: Int32(offset))
    }

    func isInLibrary(_ id: String) -> Bool {
        library.contains { $0.id == id }
    }

    @discardableResult
    func toggleLibrary(_ meta: Meta) -> Bool {
        core.toggleLibrary(meta: meta)
    }
}
