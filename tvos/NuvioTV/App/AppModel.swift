import AVFoundation
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
    @Published private(set) var watched: Set<String> = []
    @Published private(set) var traktWatchlist: [MetaPreview] = []
    @Published private(set) var trackerSyncError: String?

    private var subscriptions: [CoreSubscription] = []

    init(profileIndex: Int32? = nil) {
        if let profileIndex {
            core = NuvioCoreFactoryKt.createNuvioCoreForProfile(config: AppConfig.load(), profileIndex: profileIndex)
        } else {
            core = NuvioCoreFactoryKt.createNuvioCore(config: AppConfig.load())
        }
        subscriptions.append(core.observeAddons { [weak self] list in
            MainActor.assumeIsolated { self?.addons = list }
        })
        subscriptions.append(core.observeContinueWatching { [weak self] list in
            MainActor.assumeIsolated { self?.continueWatching = list }
        })
        subscriptions.append(core.observeLibrary { [weak self] list in
            MainActor.assumeIsolated { self?.library = list }
        })
        subscriptions.append(core.observeWatched { [weak self] set in
            MainActor.assumeIsolated { self?.watched = set }
        })
    }

    var settings: AppSettings { core.settings }

    var currentProfile: UserProfile? {
        core.profiles.profiles.first { $0.index == core.profileIndex }
    }

    func shutdown() {
        subscriptions.forEach { $0.cancel() }
        subscriptions.removeAll()
        core.close()
    }

    @Published private(set) var cloudSyncError: String?
    @Published private(set) var serverLibraries: [ServerLibrary] = []

    /** Reloads Jellyfin / Emby libraries for Home. */
    func serversChanged() {
        Task { @MainActor in
            var all: [ServerLibrary] = []
            for server in core.mediaServers.servers {
                all += (try? await core.mediaServers.libraries(server: server)) ?? []
            }
            serverLibraries = all
        }
    }

    func start() async {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        await syncCloud()
        await refreshAddons()
        serversChanged()
        await syncTrackers()
    }

    /** Nuvio account sync (add-ons, library, progress, watched) shared with the Android app. */
    func syncCloud() async {
        cloudSyncError = (try? await core.syncCloud()) ?? nil
    }

    func syncTrackers() async {
        trackerSyncError = try? await core.syncTrackers()
        traktWatchlist = core.traktWatchlistCache
    }

    func isWatched(_ meta: Meta, _ video: Video?) -> Bool {
        watched.contains(core.watchedKey(meta: meta, video: video))
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
