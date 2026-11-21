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

    /// With a Jellyfin / Emby server signed in, up next episodes get their own "Next Up" row under
    /// Continue Watching (as on Jellyfin's home); otherwise they stay in Continue Watching.
    var splitsNextUp: Bool { !core.mediaServers.servers.isEmpty }

    /// Settings > Home "Separate Upcoming row": unaired next episodes get their own row.
    private func goesToUpcoming(_ progress: WatchProgress) -> Bool {
        settings.separateUpcomingRow && core.isUpcoming(progress: progress)
    }

    /// The Continue Watching row: everything, or only titles in progress when Next Up is split off.
    var resumeItems: [WatchProgress] {
        let list = continueWatching.filter { !goesToUpcoming($0) }
        return splitsNextUp ? list.filter { !core.isUpNext(progress: $0) } : list
    }

    /// The Next Up row (empty unless split off): next episodes to start, most recently watched show first.
    var nextUpItems: [WatchProgress] {
        splitsNextUp ? continueWatching.filter { core.isUpNext(progress: $0) && !goesToUpcoming($0) } : []
    }

    /// The Upcoming row (Android's separate Upcoming row): next episodes that haven't aired, soonest first.
    var upcomingItems: [WatchProgress] {
        guard settings.separateUpcomingRow else { return [] }
        return continueWatching.filter { core.isUpcoming(progress: $0) }
            .sorted { ($0.releasedAtMs?.int64Value ?? .max) < ($1.releasedAtMs?.int64Value ?? .max) }
    }

    /// A row under Continue Watching (Next Up or Upcoming).
    struct ProgressRow: Identifiable {
        let id: String
        let title: String
        let items: [WatchProgress]
    }

    /// Next Up and Upcoming in the order chosen in Settings > Home (Upcoming first when
    /// "Upcoming above Next Up" is on); empty rows left out.
    var rowsBelowContinueWatching: [ProgressRow] {
        let nextUp = ProgressRow(id: "nextup", title: "Next Up", items: nextUpItems)
        let upcoming = ProgressRow(id: "upcoming", title: "Upcoming", items: upcomingItems)
        let ordered = settings.upcomingAboveNextUp ? [upcoming, nextUp] : [nextUp, upcoming]
        return ordered.filter { !$0.items.isEmpty }
    }

    /// Re-reads Continue Watching after a setting that filters it changed.
    func reloadContinueWatching() {
        continueWatching = core.continueWatching()
    }
    @Published private(set) var library: [SavedItem] = []
    /// Imported Nuvio collections (Home rows, read-only, this device only).
    @Published private(set) var nuvioCollections: [NuvioCollection] = []
    @Published private(set) var isRefreshingAddons = false
    @Published private(set) var failedAddonUrls: [String] = []
    @Published private(set) var watched: Set<String> = []
    @Published private(set) var traktWatchlist: [MetaPreview] = []
    @Published private(set) var mdbListWatchlist: [MetaPreview] = []
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
            MainActor.assumeIsolated {
                self?.continueWatching = list
                self?.preloadContinueWatchingPreviews()
            }
        })
        subscriptions.append(core.observeNuvioCollections { [weak self] list in
            MainActor.assumeIsolated { self?.nuvioCollections = list }
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
    /// Favourites on each Jellyfin / Emby server (Library tab).
    @Published private(set) var serverFavorites: [ServerFavorites] = []

    struct ServerFavorites: Identifiable {
        let id: String
        let serverName: String
        let items: [MetaPreview]
    }

    /// Reloads the Jellyfin / Emby favourites shown in the Library.
    func reloadServerFavorites() async {
        var result: [ServerFavorites] = []
        for server in core.mediaServers.servers {
            let items = (try? await core.mediaServers.favorites(server: server, limit: 300)) ?? []
            if !items.isEmpty { result.append(ServerFavorites(id: server.id, serverName: server.name, items: items)) }
        }
        serverFavorites = result
    }

    /** Reloads Jellyfin / Emby libraries for Home. */
    /// True while Jellyfin / Emby libraries are (re)loading.
    @Published private(set) var isLoadingServerLibraries = false

    func serversChanged() {
        // Set before the task starts so Home never flashes "Nothing to show yet" in between.
        isLoadingServerLibraries = !core.mediaServers.servers.isEmpty
        Task { @MainActor in
            var all: [ServerLibrary] = []
            for server in core.mediaServers.servers {
                all += (try? await core.mediaServers.libraries(server: server)) ?? []
            }
            serverLibraries = all
            isLoadingServerLibraries = false
            await reloadServerFavorites()
        }
    }

    func start() async {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        serversChanged()
        await syncCloud()
        await refreshAddons()
        await syncTrackers()
    }

    /** Nuvio account sync (add-ons, library, progress, watched) shared with the Android app. */
    func syncCloud() async {
        cloudSyncError = (try? await core.syncCloud()) ?? nil
    }

    func syncTrackers() async {
        trackerSyncError = try? await core.syncTrackers()
        traktWatchlist = core.traktWatchlistCache
        mdbListWatchlist = core.mdbListWatchlistCache
        // Fully watched shows from Trakt / Simkl / AniList: cards re-read their watched badge.
        objectWillChange.send()
    }

    /// Full previews for Continue Watching items (logo, description…), keyed by content id.
    private(set) var continueWatchingPreviews: [String: MetaPreview] = [:]
    private var loadingPreviews: Set<String> = []

    func continueWatchingPreview(_ progress: WatchProgress) async -> MetaPreview {
        if let cached = continueWatchingPreviews[progress.contentId] { return cached }
        let preview = (try? await core.continueWatchingPreview(progress: progress)) ?? core.previewFor(progress: progress)
        continueWatchingPreviews[progress.contentId] = preview
        return preview
    }

    /// Loads full previews for the first Continue Watching items so the hero is ready on focus.
    func preloadContinueWatchingPreviews() {
        for progress in continueWatching.prefix(10)
        where continueWatchingPreviews[progress.contentId] == nil && !loadingPreviews.contains(progress.contentId) {
            loadingPreviews.insert(progress.contentId)
            Task { @MainActor in
                _ = await continueWatchingPreview(progress)
                loadingPreviews.remove(progress.contentId)
            }
        }
    }

    func isWatched(_ meta: Meta, _ video: Video?) -> Bool {
        watched.contains(core.watchedKey(meta: meta, video: video))
    }

    /// Set once the first add-on refresh has finished (with or without add-ons installed).
    @Published private(set) var hasLoadedAddons = false

    func refreshAddons() async {
        isRefreshingAddons = true
        defer { isRefreshingAddons = false; hasLoadedAddons = true }
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

    /// Saved here, or a favourite on a Jellyfin / Emby server.
    func isInLibrary(_ id: String) -> Bool {
        library.contains { $0.id == id } || isServerFavorite(id)
    }

    func isServerFavorite(_ id: String) -> Bool {
        serverFavoriteIds.contains(id)
    }

    /// Ids (and IMDb ids) of every Jellyfin / Emby favourite, to keep the Library free of duplicates.
    /// Includes each favourite's other ids ("tmdb:…") so add-on items with TMDB ids match too.
    var serverFavoriteIds: Set<String> {
        Set(serverFavorites.flatMap { group in
            group.items.flatMap { item in
                [item.id, item.imdbId].compactMap { $0 } + core.mediaServers.aliasesFor(id: item.id)
            }
        })
    }

    /// Details page Add to / Remove from Library.
    @discardableResult
    func toggleLibrary(_ meta: Meta) -> Bool {
        return setInLibrary(id: meta.id, imdbId: meta.imdbId,
                            isShow: meta.isSeriesLike) { core.toggleLibrary(meta: meta) }
    }

    /// Card / Continue Watching menu Add to / Remove from Library.
    @discardableResult
    func toggleLibrary(preview: MetaPreview) -> Bool {
        return setInLibrary(id: preview.id, imdbId: preview.imdbId,
                            isShow: preview.apiType != "movie") {
            core.toggleLibraryPreview(preview: preview)
        }
    }

    /// Flips the title in or out of the Library everywhere: this Apple TV (plus Trakt watchlist and
    /// the Nuvio account, via the core) and as a favourite on Jellyfin / Emby servers.
    private func setInLibrary(id: String, imdbId: String?, isShow: Bool, toggleLocal: () -> Bool) -> Bool {
        let wasSaved = isInLibrary(id) || (imdbId.map(isServerFavorite) ?? false)
        let savedLocally = library.contains { $0.id == id }
        // Only touch the local library when it should change (a server-only favourite isn't saved here).
        if savedLocally == wasSaved { _ = toggleLocal() }
        let saved = !wasSaved
        if !core.mediaServers.servers.isEmpty {
            Task { @MainActor in
                // Found on the server by its id, IMDb id or TMDB id (converted through TMDB when needed).
                _ = try? await core.setServerFavorite(id: id, imdbId: imdbId, isShow: isShow, favorite: saved)
                await reloadServerFavorites()
            }
        }
        return saved
    }
}
