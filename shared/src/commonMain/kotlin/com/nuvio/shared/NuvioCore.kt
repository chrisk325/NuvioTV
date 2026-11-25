package com.nuvio.shared

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.AddonStreams
import com.nuvio.shared.model.CatalogRow
import com.nuvio.shared.model.Meta
import com.nuvio.shared.model.MetaCastMember
import com.nuvio.shared.model.ContentType
import com.nuvio.shared.model.PosterShape
import com.nuvio.shared.model.MetaPreview
import com.nuvio.shared.model.SavedItem
import com.nuvio.shared.model.Stream
import com.nuvio.shared.model.StreamsUpdate
import com.nuvio.shared.model.WatchProgress
import com.nuvio.shared.model.isSeriesLikeType
import com.nuvio.shared.repository.AddonRepository
import com.nuvio.shared.repository.CatalogRepository
import com.nuvio.shared.repository.CatalogSlot
import com.nuvio.shared.repository.LibraryRepository
import com.nuvio.shared.repository.MetaRepository
import com.nuvio.shared.repository.SearchRepository
import com.nuvio.shared.repository.StreamRepository
import com.nuvio.shared.repository.SubtitleRepository
import com.nuvio.shared.settings.AppSettings
import com.nuvio.shared.tmdb.TmdbEnrichment
import com.nuvio.shared.tmdb.TmdbPersonDetail
import com.nuvio.shared.tmdb.TmdbService
import com.nuvio.shared.tracking.AniListService
import com.nuvio.shared.tracking.ExternalRating
import com.nuvio.shared.tracking.MdbListService
import com.nuvio.shared.tracking.ScrobbleAction
import com.nuvio.shared.tracking.SimklService
import com.nuvio.shared.tracking.TrackedMedia
import com.nuvio.shared.tracking.TraktService
import com.nuvio.shared.tracking.WatchedRepository
import com.nuvio.shared.model.Video
import com.nuvio.shared.collections.sortedForCollection
import kotlinx.coroutines.CancellationException
import com.nuvio.shared.model.Subtitle
import com.nuvio.shared.repository.WatchProgressRepository
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import com.nuvio.shared.util.isReleaseInFuture
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull

data class CalendarEntry(val show: MetaPreview, val video: Video, val releaseEpochMs: Long)

/** Merged details (add-on meta + enabled TMDB groups). People with id 0 have no TMDB page. */
data class DetailContent(
    val meta: Meta,
    val people: List<com.nuvio.shared.tmdb.TmdbPerson>,
    val tmdbRating: String?,
    val tagline: String?,
    val recommendations: List<MetaPreview>,
    val collectionName: String?,
    val collectionParts: List<MetaPreview>,
    /** "Movie Details" / "Show Details" table at the bottom of the details page. */
    val facts: List<DetailFact> = emptyList()
)

/** One row of the details table, e.g. "Budget" → "$260,000,000". */
data class DetailFact(val label: String, val value: String)

/** Handle returned to Swift for observations; call [cancel] when the view goes away. */
interface CoreSubscription {
    fun cancel()
}

private class JobCancellable(private val job: Job) : CoreSubscription {
    override fun cancel() = job.cancel()
}

/** What the media servers last reported as in progress (video id → their update time). */
private const val SERVER_RESUME_KEY = "mediaserver.resume.v1"
/** Imported Nuvio collections (this device only, never synced). */
private const val NUVIO_COLLECTIONS_KEY = "collections.imported.v1"

/** Longest wait for a media server to describe a title before TMDB is used instead. */
private const val SERVER_LOOKUP_MS = 8_000L
/** Shows looked up per sync for up next air dates the trackers don't give. */
private const val MAX_AIR_DATE_LOOKUPS = 120
/** Up next source holding unaired next episodes of shows that are caught up (the Upcoming row). */
private const val UPCOMING_SOURCE = "upcoming"
/** Shows watched within this many days are checked for an upcoming episode (Android's default cap). */
private const val UPCOMING_SEED_WINDOW_MS = 60L * 86_400_000L
private const val MAX_UPCOMING_LOOKUPS = 60
private const val LOCAL_SEEDS_KEY = "upcoming.seeds.local"
private const val MAX_LOCAL_SEEDS = 300
private const val AIR_DATE_PARALLEL = 6
private const val AIR_DATE_RETRIES = 2
private const val AIR_DATE_RETRY_DELAY_MS = 30_000L

/**
 * Single entry point for platform UIs (the tvOS SwiftUI app). Everything here is plain classes,
 * suspend functions (Swift `async throws`) and callbacks, so no Flow bridging library is needed.
 * Callbacks run on [dispatcher] (the main thread on tvOS).
 */
class NuvioCore(
    store: KeyValueStore,
    httpClient: HttpClient,
    dispatcher: CoroutineDispatcher,
    val config: NuvioConfig = NuvioConfig.EMPTY,
    /** Active profile (1 = primary); its data lives in [store]. */
    val profileIndex: Int = 1,
    /** Device-wide store for the profile list and the shared Nuvio account session. */
    deviceStore: KeyValueStore = store,
    /** Large caches (MDBList list contents); defaults to [store]. */
    cacheStore: KeyValueStore? = null
) {
    internal val store = store
    internal val http = httpClient
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
    /** The thread callbacks run on (main on tvOS); Live TV state changes stay on it. */
    private val mainDispatcher = dispatcher
    private val client = StremioClient(httpClient)

    val addonRepository = AddonRepository(client, store)
    val catalogRepository = CatalogRepository(client)
    val searchRepository = SearchRepository(catalogRepository)
    val metaRepository = MetaRepository(client)
    val streamRepository = StreamRepository(client)
    val watchProgress = WatchProgressRepository(store)
    val library = LibraryRepository(store)
    val subtitleRepository = SubtitleRepository(client)
    val settings = AppSettings(store)
    val debrid = com.nuvio.shared.debrid.DebridService(httpClient, store, config.premiumizeClientId)
    val watched = WatchedRepository(store)
    val trakt = TraktService(httpClient, store, config.traktClientId, config.traktClientSecret)
    val simkl = SimklService(httpClient, store, config.simklClientId, config.simklAppName.ifBlank { "nuvio" })
    val aniList = AniListService(httpClient, store, config.aniListClientId)
    val mdbList = MdbListService(httpClient)
    /** MDBList as a tracker (OAuth sign-in): scrobbles, watched history, progress, watchlist. */
    val mdbListTracker = com.nuvio.shared.tracking.MdbListTracker(httpClient, store, config.mdbListClientId, cacheStore ?: store)
    val tmdb = TmdbService(httpClient, config.tmdbApiKey)
    val skipSegments = com.nuvio.shared.tracking.SkipSegmentsService(httpClient, config.introDbApiUrl)
    val liveTv = com.nuvio.shared.livetv.LiveTvRepository(httpClient, store, cacheStore ?: store)
    val mediaServers = com.nuvio.shared.mediaserver.MediaServerService(httpClient, store)
    val cloud = com.nuvio.shared.cloud.NuvioCloud(httpClient, store, config.supabaseUrl.trimEnd('/'), config.supabaseAnonKey, profileIndex, deviceStore)
    val profiles = com.nuvio.shared.settings.ProfileStore(deviceStore)

    // --- Add-ons ---------------------------------------------------------------------------

    val addons: List<Addon> get() = addonRepository.currentAddons

    fun observeAddons(onChange: (List<Addon>) -> Unit): CoreSubscription =
        observe(addonRepository.addons, onChange)

    /** Refreshes manifests; returns the URLs that failed. */
    @Throws(Exception::class)
    suspend fun refreshAddons(): List<String> = addonRepository.refreshAll()

    @Throws(Exception::class)
    suspend fun installAddon(url: String): Addon = addonRepository.install(url).also { pushAddonsToCloud() }

    @Throws(Exception::class)
    suspend fun removeAddon(baseUrl: String) { addonRepository.remove(baseUrl); pushAddonsToCloud() }

    @Throws(Exception::class)
    suspend fun setAddonEnabled(baseUrl: String, enabled: Boolean) { addonRepository.setEnabled(baseUrl, enabled); pushAddonsToCloud() }

    @Throws(Exception::class)
    suspend fun moveAddon(baseUrl: String, offset: Int) { addonRepository.move(baseUrl, offset); pushAddonsToCloud() }

    private fun pushAddonsToCloud() {
        if (!cloud.isSignedIn) return
        val snapshot = addonRepository.syncSnapshot.mapIndexed { i, (url, enabled) -> com.nuvio.shared.cloud.CloudAddon(url, enabled, i) }
        scope.launch { runCatching { cloud.pushAddons(snapshot) } }
    }

    /**
     * Two-way Nuvio account sync (add-ons, library, progress, watched) with the Android app.
     * The account wins for add-ons; library / watched are merged; progress keeps the newest.
     * Returns an error text, or null.
     */
    @Throws(Exception::class)
    suspend fun syncCloud(): String? {
        if (!cloud.isSignedIn) return null
        // Like Android pullRemoteData: the account's add-on list is applied here (it never pushes
        // the local list just because the account came back empty), and each part syncs on its
        // own so one failure doesn't stop the rest. Returns the first error, naming the part.
        var error: String? = null
        suspend fun part(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (error == null) error = "$name: ${e.message ?: "sync failed"}"
            }
        }
        part("Add-ons") {
            val remoteAddons = cloud.pullAddons()
            if (remoteAddons.isNotEmpty()) addonRepository.replaceFromRemote(remoteAddons.map { it.url to it.enabled })
        }
        part("Library") {
            val remoteLibrary = cloud.pullLibrary()
            val localIds = library.items.value.map { it.id }.toSet()
            remoteLibrary.filter { it.id !in localIds }.forEach { library.toggle(it) }
            val remoteIds = remoteLibrary.map { it.id }.toSet()
            cloud.pushLibrary(library.items.value.filter { it.id !in remoteIds })
        }
        part("Watch progress") {
            cloud.pullProgress().forEach { remote ->
                val local = watchProgress.progressFor(remote.videoId)
                if (local == null || local.updatedAtMs < remote.updatedAtMs) {
                    val imdb = remote.contentId.takeIf { it.startsWith("tt") }
                    watchProgress.update(remote.copy(
                        name = local?.name ?: library.items.value.firstOrNull { it.id == remote.contentId }?.name ?: remote.contentId,
                        poster = local?.poster ?: imdb?.let(TraktService::poster),
                        background = local?.background ?: imdb?.let(TraktService::background),
                        logo = local?.logo,
                        episodeTitle = local?.episodeTitle
                    ))
                }
            }
            cloud.pushProgress(watchProgress.entries.value.take(100))
        }
        part("Watched history") {
            watched.addAll(cloud.pullWatched())
        }
        return error
    }

    // --- Browsing --------------------------------------------------------------------------

    /** Home rows, without the catalogs hidden in Settings. */
    fun homeSlots(): List<CatalogSlot> {
        val hidden = settings.hiddenCatalogKeys.toSet()
        return allHomeSlots().filterNot { it.key in hidden }
    }

    /**
     * Hero items (port of HomeViewModelCatalogPipeline's hero selection): the chosen hero
     * catalogs, else every loaded row, interleaved one item per row, artwork first, de-duplicated.
     */
    fun heroItems(rows: List<CatalogRow>, limit: Int): List<MetaPreview> {
        val chosen = settings.heroCatalogKeys.toSet()
        val selected = if (chosen.isEmpty()) rows else rows.filter { row ->
            allHomeSlots().any { it.key in chosen && it.addon.baseUrl == row.addonBaseUrl && it.catalog.id == row.catalogId && it.catalog.apiType == row.apiType }
        }
        fun interleave(filter: (MetaPreview) -> Boolean): List<MetaPreview> {
            val seen = HashSet<String>()
            val lists = selected.map { row -> row.items.filter(filter) }
            val out = mutableListOf<MetaPreview>()
            var index = 0
            while (out.size < limit && lists.any { index < it.size }) {
                lists.forEach { list -> list.getOrNull(index)?.let { if (seen.add(it.id) && out.size < limit) out += it } }
                index++
            }
            return out
        }
        return interleave { it.background != null || it.landscapePoster != null }.ifEmpty { interleave { true } }
    }

    fun allHomeSlots(): List<CatalogSlot> = catalogRepository.homeSlots(addonRepository.currentAddons)

    @Throws(Exception::class)
    suspend fun loadCatalog(slot: CatalogSlot, skip: Int): CatalogRow = catalogRepository.loadSlot(slot, skip)

    /** Catalog page filtered by genre ("" = all), for the "See all" grid. */
    @Throws(Exception::class)
    suspend fun loadCatalogFiltered(slot: CatalogSlot, skip: Int, genre: String): CatalogRow =
        catalogRepository.loadRow(slot.addon, slot.catalog, skip, if (genre.isBlank()) emptyMap() else mapOf("genre" to genre))

    /**
     * Catalogs for Discover (Android's Discover): every enabled add-on's catalogs that can be
     * browsed — not search-only ones, nor ones that require something other than a genre.
     */
    fun discoverSlots(): List<CatalogSlot> =
        addonRepository.currentAddons.filter { it.enabled }.flatMap { addon ->
            addon.catalogs.filter { catalog ->
                !catalog.isSearchOnlyCatalog() &&
                    catalog.extra.filter { it.isRequired }.all { it.name.equals("genre", ignoreCase = true) } &&
                    catalog.extraRequired.all { it.equals("genre", ignoreCase = true) }
            }.map { CatalogSlot(addon, it) }
        }

    /** The catalog can't list "All": a genre must be picked. */
    fun genreRequired(slot: CatalogSlot): Boolean =
        slot.catalog.extra.any { it.name.equals("genre", ignoreCase = true) && it.isRequired } ||
            slot.catalog.extraRequired.any { it.equals("genre", ignoreCase = true) }

    /** Genre options a catalog declares (empty when it has no genre extra). */
    fun genres(slot: CatalogSlot): List<String> =
        slot.catalog.extra.firstOrNull { it.name.equals("genre", ignoreCase = true) }?.options.orEmpty()

    @Throws(Exception::class)
    suspend fun search(query: String): List<CatalogRow> =
        searchRepository.search(addonRepository.currentAddons, query)

    /** Add-on meta; "tmdb:" ids (TMDB recommendations / person credits) fall back to their IMDb id. */
    @Throws(Exception::class)
    suspend fun meta(type: String, id: String, sourceAddonBaseUrl: String?): Meta {
        // Jellyfin / Emby titles without an IMDb id: only the server knows them.
        if (mediaServers.isNativeId(id)) {
            return mediaServers.meta(type, id) ?: throw IllegalStateException("This title is no longer on your media server")
        }
        try {
            val meta = metaRepository.getMeta(addonRepository.currentAddons, type, id, sourceAddonBaseUrl)
            return fillFromMediaServer(meta, type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val isShow = type != "movie"
            val tmdbId = id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()
            val imdb = id.substringBefore(':').takeIf { it.startsWith("tt") }
                ?: tmdbId?.let { runCatching { tmdb.imdbIdFor(it, isShow) }.getOrNull() }
            // "tmdb:" ids (More Like This, Collection, actor pages): add-ons by the IMDb id.
            if (imdb != null && !id.startsWith("tt")) {
                try {
                    return fillFromMediaServer(metaRepository.getMeta(addonRepository.currentAddons, type, imdb, null), type)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                }
            }
            // Titles from a Jellyfin / Emby server that no add-on describes: use the server's data.
            // Bounded: catalog-backed servers (AIOMetadata's Jellyfin API) can take very long to
            // list or search, and TMDB below describes the title anyway.
            if (mediaServers.servers.isNotEmpty()) {
                val fromServer = withTimeoutOrNull(SERVER_LOOKUP_MS) {
                    var found: Meta? = null
                    for (serverId in listOfNotNull(imdb, id.takeIf { it.startsWith("tmdb:") })) {
                        found = try {
                            mediaServers.meta(type, serverId)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Exception) {
                            null
                        }
                        if (found != null) break
                    }
                    found
                }
                if (fromServer != null) return fromServer
            }
            // Last resort: the title as TMDB describes it (streams still use the IMDb id).
            val tmdbTarget = tmdbId ?: imdb?.let { runCatching { tmdb.findByImdb(it, isShow) }.getOrNull() }
            tmdbTarget?.let { tid ->
                runCatching { tmdbMeta(tid, isShow, imdb) }.getOrNull()?.let { return it }
            }
            if (mediaServers.servers.isNotEmpty() && addonRepository.currentAddons.none { it.enabled }) {
                throw IllegalStateException(
                    "Couldn't find this title on ${mediaServers.servers.joinToString { it.name }}. " +
                        "Check that the server is reachable and the title is in a Movies or Shows library."
                )
            }
            throw e
        }
    }

    /** Details built from TMDB alone, for titles no add-on or media server describes. */
    private suspend fun tmdbMeta(tmdbId: Int, isShow: Boolean, knownImdb: String?): Meta? {
        val t = tmdb.enrich("tmdb:$tmdbId", null, isShow, settings.tmdbLanguage) ?: return null
        val imdb = knownImdb ?: t.imdbId
        val baseId = imdb ?: "tmdb:$tmdbId"
        val videos = if (!isShow) emptyList() else t.seasonNumbers.take(40).flatMap { season ->
            runCatching { tmdb.seasonEpisodes(tmdbId, season, settings.tmdbLanguage) }.getOrDefault(emptyList()).map { ep ->
                Video(
                    id = "$baseId:${ep.season}:${ep.episode}",
                    title = ep.title ?: "Episode ${ep.episode}",
                    released = null,
                    thumbnail = ep.thumbnail,
                    season = ep.season,
                    episode = ep.episode,
                    overview = ep.overview,
                    runtimeMinutes = ep.runtimeMinutes ?: 0,
                    seasonPoster = ep.seasonPoster?.takeIf { settings.tmdbUseSeasonPosters }
                )
            }
        }
        return Meta(
            id = baseId,
            type = if (isShow) ContentType.SERIES else ContentType.MOVIE,
            name = t.localizedTitle ?: return null,
            poster = t.poster,
            posterShape = PosterShape.POSTER,
            background = t.backdrop,
            logo = t.logo,
            description = t.overview,
            releaseInfo = t.releaseDate?.take(4),
            status = t.status,
            imdbRating = t.rating,
            genres = t.genres,
            runtime = t.runtimeMinutes?.let { "$it min" },
            director = t.directors,
            writer = t.writers,
            cast = t.cast.map { it.name },
            castMembers = t.cast.map { MetaCastMember(it.name, it.role, it.photo, it.id) },
            videos = videos,
            productionCompanies = t.productionCompanies,
            networks = t.networks,
            ageRating = t.certification,
            country = t.countries.takeIf { it.isNotEmpty() }?.joinToString(", "),
            awards = null,
            language = t.language,
            links = emptyList(),
            trailerYtIds = t.trailerYoutubeKeys,
            imdbId = imdb,
            released = t.releaseDate,
            ratingSource = "tmdb"
        )
    }

    /**
     * Add-ons often return a bare entry for new titles (a name and a poster). When a Jellyfin /
     * Emby server has the title, its metadata fills whatever the add-on left out; add-on data wins
     * where present.
     */
    private suspend fun fillFromMediaServer(meta: Meta, type: String): Meta {
        if (mediaServers.servers.isEmpty()) return meta
        val sparse = meta.description.isNullOrBlank() || meta.background == null || meta.genres.isEmpty() ||
            (meta.cast.isEmpty() && meta.castMembers.isEmpty()) || (meta.isSeriesLike && meta.videos.isEmpty())
        if (!sparse) return meta
        val imdb = (meta.imdbId ?: meta.id).substringBefore(':').takeIf { it.startsWith("tt") } ?: return meta
        val server = runCatching { mediaServers.meta(type, imdb) }.getOrNull() ?: return meta
        return mergeMeta(meta, server)
    }

    // --- TMDB / ratings ---------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun tmdbEnrichment(meta: Meta): TmdbEnrichment? {
        if (!settings.tmdbEnrichment || !tmdb.isConfigured) return null
        return tmdb.enrich(meta.id, meta.imdbId, meta.isSeriesLike, settings.tmdbLanguage)
    }

    /**
     * The details screen's data: add-on meta with TMDB layered on per group, exactly like
     * MetaDetailsViewModel on Android. With enrichment (or a group) off, the add-on's own
     * artwork, description, cast (with photos), episodes etc. are used.
     */
    @Throws(Exception::class)
    suspend fun details(meta: Meta): DetailContent {
        // TMDB is fetched whenever a key exists: cast that opens actor pages, related titles,
        // collections and the details table come from it. The master switch only decides
        // whether TMDB also replaces the add-on's metadata (artwork, texts, episodes…).
        val tmdbData = if (!tmdb.isConfigured) null else try {
            tmdb.enrich(meta.id, meta.imdbId, meta.isSeriesLike, settings.tmdbLanguage)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
        val e = tmdbData?.takeIf { settings.tmdbEnrichment }
        val s = settings
        var m = meta
        if (e != null && s.tmdbUseArtwork) {
            m = m.copy(background = e.backdrop ?: m.background, logo = e.logo ?: m.logo)
        }
        if (e != null && s.tmdbUseBasicInfo) {
            m = m.copy(name = e.localizedTitle ?: m.name, description = e.overview ?: m.description)
            if (e.genres.isNotEmpty()) m = m.copy(genres = e.genres)
        }
        if (e != null && s.tmdbUseDetails) {
            m = m.copy(
                runtime = e.runtimeMinutes?.let { "$it min" } ?: m.runtime,
                status = e.status ?: m.status,
                ageRating = e.certification ?: m.ageRating,
                country = e.countries.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: m.country,
                language = e.language ?: m.language
            )
        }
        val tmdbPeople = if (e != null && s.tmdbUseCredits) e.crew + e.cast else emptyList()
        if (e != null && s.tmdbUseCredits) {
            m = m.copy(
                cast = e.cast.takeIf { it.isNotEmpty() }?.map { it.name } ?: m.cast,
                director = e.directors.ifEmpty { m.director },
                writer = e.writers.ifEmpty { m.writer }
            )
        }
        if (e != null && s.tmdbUseProductions && e.productionCompanies.isNotEmpty()) m = m.copy(productionCompanies = e.productionCompanies)
        if (e != null && s.tmdbUseNetworks && e.networks.isNotEmpty()) m = m.copy(networks = e.networks)
        if (e != null && s.tmdbUseTrailers && e.trailerYoutubeKeys.isNotEmpty()) {
            m = m.copy(trailerYtIds = (m.trailerYtIds + e.trailerYoutubeKeys).distinct())
        }
        // Episodes and season posters (Android: needsEpisodes = useEpisodes || useSeasonPosters).
        if (e != null && e.isShow && (s.tmdbUseEpisodes || s.tmdbUseSeasonPosters) && m.videos.isNotEmpty()) {
            val seasons = m.videos.mapNotNull { it.season }.distinct().filter { it > 0 }.take(30)
            val episodes = seasons.flatMap { season ->
                runCatching { tmdb.seasonEpisodes(e.tmdbId, season, s.tmdbLanguage) }.getOrDefault(emptyList())
            }.associateBy { it.season to it.episode }
            if (episodes.isNotEmpty()) {
                m = m.copy(videos = m.videos.map { v ->
                    val ep = if (v.season != null && v.episode != null) episodes[v.season to v.episode] else null
                    if (ep == null) v else v.copy(
                        title = if (s.tmdbUseEpisodes) ep.title ?: v.title else v.title,
                        overview = if (s.tmdbUseEpisodes) ep.overview ?: v.overview else v.overview,
                        thumbnail = if (s.tmdbUseEpisodes) ep.thumbnail ?: v.thumbnail else v.thumbnail,
                        runtimeMinutes = if (s.tmdbUseEpisodes) ep.runtimeMinutes ?: v.runtimeMinutes else v.runtimeMinutes,
                        seasonPoster = if (s.tmdbUseSeasonPosters) ep.seasonPoster ?: v.seasonPoster else v.seasonPoster
                    )
                })
            }
        }
        // People row: TMDB credits when enabled, otherwise the add-on's own cast (app_extras, with
        // photos). Without enrichment, TMDB's cast is still used when the add-on's people have no
        // TMDB ids, so every actor opens their page (filmography).
        val addonPeople = meta.castMembers.map { com.nuvio.shared.tmdb.TmdbPerson(it.tmdbId ?: 0, it.name, it.character, it.photo) }
        val people = tmdbPeople.ifEmpty {
            val linkable = addonPeople.any { it.id > 0 }
            if (e == null && tmdbData != null && !linkable && tmdbData.cast.isNotEmpty()) tmdbData.crew + tmdbData.cast else addonPeople
        }
        // Related titles and collections: the group switches apply when enrichment is on; without
        // it they still show (they only add rows, nothing of the add-on's is replaced).
        val related = tmdbData?.takeIf { e == null || s.tmdbUseMoreLikeThis }
        val collection = tmdbData?.takeIf { e == null || s.tmdbUseCollections }
        return DetailContent(
            meta = m,
            people = people,
            tmdbRating = if (e != null && s.tmdbUseBasicInfo) e.rating else null,
            tagline = if (e != null && s.tmdbUseBasicInfo) e.tagline else null,
            recommendations = related?.recommendations.orEmpty(),
            collectionName = collection?.collectionName,
            collectionParts = collection?.collectionParts.orEmpty(),
            facts = detailFacts(m, tmdbData)
        )
    }

    /** Status, release, runtime, certification, origin, language, budget / revenue or seasons. */
    private fun detailFacts(meta: Meta, t: com.nuvio.shared.tmdb.TmdbEnrichment?): List<DetailFact> {
        val facts = mutableListOf<DetailFact>()
        fun add(label: String, value: String?) { value?.trim()?.takeIf { it.isNotEmpty() }?.let { facts += DetailFact(label, it) } }
        add("Status", t?.status ?: meta.status)
        add(if (meta.isSeriesLike) "First Aired" else "Release Date", t?.releaseDate?.let(::formatDate) ?: meta.releaseInfo)
        if (meta.isSeriesLike) {
            add("Last Aired", t?.lastAirDate?.let(::formatDate))
            add("Seasons", t?.seasonCount?.toString())
            add("Episodes", t?.episodeCount?.toString())
            add("Network", (t?.networks?.takeIf { it.isNotEmpty() } ?: meta.networks).take(3).joinToString(", "))
        }
        add("Runtime", t?.runtimeMinutes?.let(::formatRuntime) ?: meta.runtime?.let { raw ->
            // "173 min" / "173" from add-ons → "2h 53m".
            Regex("""^\s*(\d+)\s*(m|min|mins|minutes?)?\.?\s*$""", RegexOption.IGNORE_CASE).find(raw)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }?.let(::formatRuntime) ?: raw
        })
        add("Certification", t?.certification ?: meta.ageRating)
        add("Origin Country", t?.originCountries?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: meta.country)
        add("Original Language", t?.language?.uppercase() ?: meta.language)
        if (!meta.isSeriesLike) {
            add("Budget", t?.budget?.let(::formatMoney))
            add("Revenue", t?.revenue?.let(::formatMoney))
        }
        add("Production", (t?.productionCompanies ?: meta.productionCompanies).take(3).joinToString(", "))
        return facts
    }

    private fun formatRuntime(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

    /** 147803121 → "$147,803,121". */
    private fun formatMoney(amount: Long): String =
        "$" + amount.toString().reversed().chunked(3).joinToString(",").reversed()

    /** "2026-03-14" → "14 Mar 2026". */
    private fun formatDate(raw: String): String {
        val parts = raw.take(10).split('-')
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return raw
        val day = parts.getOrNull(2)?.toIntOrNull() ?: return raw
        return "$day ${months[month - 1]} ${parts[0]}"
    }

    @Throws(Exception::class)
    suspend fun tmdbPerson(personId: Int): TmdbPersonDetail? = tmdb.person(personId, settings.tmdbLanguage)

    private val ratingsCache = LinkedHashMap<String, List<ExternalRating>>()
    /** Hero item id → its ratings, once found (the full lookup loads the title's details). */
    private val heroRatingsCache = LinkedHashMap<String, List<ExternalRating>>()

    private suspend fun cachedRatings(imdb: String, isShow: Boolean): List<ExternalRating> {
        val key = "$imdb|$isShow"
        ratingsCache[key]?.let { return it }
        val result = mdbList.ratings(settings.mdbListApiKey, imdb, isShow)
        // Empty (not found, network hiccup) isn't cached, so the next look tries again.
        if (result.isNotEmpty()) {
            ratingsCache[key] = result
            if (ratingsCache.size > 200) ratingsCache.remove(ratingsCache.keys.first())
        }
        return result
    }

    /** Details page rating logos: MDBList (IMDb, else TMDB id), else the IMDb logo with the add-on's rating. */
    @Throws(Exception::class)
    suspend fun ratings(meta: Meta): List<ExternalRating> {
        val mdb = if (settings.showMdbListRatings) mdbRatings(meta.imdbId, meta.id, meta.isSeriesLike) else emptyList()
        return mdb.ifEmpty { fallbackRating(meta.imdbRating, meta.ratingSource) }
    }

    /** MDBList ratings by IMDb id, else by the TMDB id of a "tmdb:" id; cached per title. */
    private suspend fun mdbRatings(imdbId: String?, id: String, isShow: Boolean): List<ExternalRating> {
        if (settings.mdbListApiKey.isBlank()) return emptyList()
        val imdb = imdbId?.substringBefore(':')?.takeIf { it.startsWith("tt") }
            ?: id.takeIf { it.startsWith("tt") }?.substringBefore(':')
        if (imdb != null) return cachedRatings(imdb, isShow)
        // "tmdb:" catalog ids, or the TMDB id a Jellyfin / Emby server keeps for its own titles.
        val tmdb = id.takeIf { it.startsWith("tmdb:") }?.split(':')?.getOrNull(1)
            ?: if (mediaServers.isNativeId(id)) mediaServers.tmdbIdFor(id) else null
        if (tmdb == null) return emptyList()
        val key = "tmdb:$tmdb|$isShow"
        ratingsCache[key]?.let { return it }
        // Same lookup the details page uses (by IMDb id), via TMDB's external ids; MDBList's own
        // TMDB lookup as the fallback.
        val viaImdb = tmdb.toIntOrNull()
            ?.let { tmdbId -> runCatching { this.tmdb.imdbIdFor(tmdbId, isShow) }.getOrNull() }
            ?.let { imdb -> cachedRatings(imdb, isShow) }
            .orEmpty()
        val result = viaImdb.ifEmpty { runCatching { mdbList.ratingsByTmdb(settings.mdbListApiKey, tmdb, isShow) }.getOrDefault(emptyList()) }
        if (result.isNotEmpty()) {
            ratingsCache[key] = result
            if (ratingsCache.size > 200) ratingsCache.remove(ratingsCache.keys.first())
        }
        return result
    }

    /** The title's own rating under the right logo: IMDb for add-ons, TMDB for Jellyfin / Emby and TMDB data. */
    private fun fallbackRating(raw: String?, source: String?): List<ExternalRating> {
        val rating = raw?.toDoubleOrNull()?.takeIf { it > 0 } ?: return emptyList()
        val text = kotlin.math.round(rating * 10).toInt().let { "${it / 10}.${it % 10}" }
        return if (source == "tmdb") listOf(ExternalRating("tmdb", "TMDB", text, "rating_tmdb"))
        else listOf(ExternalRating("imdb", "IMDb", text, "rating_imdb"))
    }

    /**
     * Rating logos for a Home hero item: MDBList by IMDb id, else by TMDB id ("tmdb:…" catalogs);
     * without MDBList ratings (no key, disabled, not found) the add-on's IMDb rating as the IMDb
     * logo. Cached per title.
     */
    @Throws(Exception::class)
    suspend fun heroRatings(preview: MetaPreview): List<ExternalRating> {
        val isShow = com.nuvio.shared.model.isSeriesLikeType(preview.apiType)
        val enabled = settings.showMdbListRatings && settings.showMdbListOnHero && settings.mdbListApiKey.isNotBlank()
        if (!enabled) return fallbackRating(preview.imdbRating, preview.ratingSource)
        heroRatingsCache[preview.id]?.let { return it }
        var mdb = mdbRatings(preview.imdbId, preview.id, isShow)
        if (mdb.isEmpty()) {
            // Row items (Jellyfin / Emby titles especially) often lack the IMDb id the details page
            // ends up with: load the title the same way the details page does (meta, then TMDB
            // enrichment for the ids) and look its ratings up exactly as the details page does.
            val full = runCatching { meta(preview.apiType, preview.id, preview.sourceAddonBaseUrl) }
                .onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (full != null) {
                val withIds = if (full.imdbId == null) {
                    runCatching { details(full).meta }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: full
                } else full
                mdb = mdbRatings(withIds.imdbId, withIds.id, withIds.isSeriesLike)
            }
        }
        if (mdb.isNotEmpty()) {
            heroRatingsCache[preview.id] = mdb
            if (heroRatingsCache.size > 200) heroRatingsCache.remove(heroRatingsCache.keys.first())
        }
        return mdb.ifEmpty { fallbackRating(preview.imdbRating, preview.ratingSource) }
    }

    /** Streams from every add-on, progressively; torrent streams get debrid cache badges once all arrive. */
    /**
     * [serverVideoId]: the IMDb-based id ("tt…" / "tt…:season:episode") used to find the title on
     * Jellyfin / Emby when [videoId] uses another scheme (e.g. AIOMetadata's tmdb: ids).
     */
    fun loadStreams(
        type: String,
        videoId: String,
        embedded: List<Stream>,
        serverVideoId: String?,
        serverReleased: String?,
        onUpdate: (StreamsUpdate) -> Unit
    ): CoreSubscription = observe(
        streamRepository.streams(addonRepository.currentAddons, type, videoId, embedded)
            .transform { update ->
                emit(update)
                if (!update.isComplete) return@transform
                var groups = update.groups
                if (mediaServers.servers.isNotEmpty()) {
                    val serverGroups = runCatching { serverStreams(type, videoId, serverVideoId, serverReleased) }
                        .getOrDefault(emptyList())
                    if (serverGroups.isNotEmpty()) {
                        groups = serverGroups + groups
                        emit(update.copy(groups = groups))
                    }
                }
                if (debrid.hasAnyProvider) emit(update.copy(groups = debrid.annotateCache(groups)))
            },
        onUpdate
    )

    /**
     * Jellyfin / Emby streams for a video: tries the add-on id, the IMDb-based id and TMDB ids
     * (converted through TMDB when only one kind is known), so a server that identifies the show
     * by another provider still finds it.
     */
    private suspend fun serverStreams(type: String, videoId: String, serverVideoId: String?, released: String?): List<AddonStreams> {
        val first = listOfNotNull(videoId.takeIf { it.startsWith("tt") }, serverVideoId, videoId).distinct()
        val found = mediaServers.streamsFor(type, first, released)
        if (found.isNotEmpty()) return found
        val alternates = listOfNotNull(
            imdbVideoIdFromTmdb(type, videoId),
            tmdbVideoIdFromImdb(type, videoId.takeIf { it.startsWith("tt") } ?: serverVideoId)
        ).filter { it !in first }
        return if (alternates.isEmpty()) emptyList() else mediaServers.streamsFor(type, alternates, released)
    }

    /**
     * Favourite / unfavourite a title on Jellyfin / Emby. Tries its id, IMDb id and TMDB id, and
     * converts between IMDb and TMDB through TMDB when only one is known (search results from
     * TMDB-based add-ons carry only "tmdb:" ids). Returns how many servers were updated.
     */
    @Throws(Exception::class)
    suspend fun setServerFavorite(id: String, imdbId: String?, isShow: Boolean, favorite: Boolean): Int {
        if (mediaServers.servers.isEmpty()) return 0
        val type = if (isShow) "series" else "movie"
        val imdb = imdbId?.substringBefore(':')?.takeIf { it.startsWith("tt") } ?: id.substringBefore(':').takeIf { it.startsWith("tt") }
        val tmdbIdString = id.takeIf { it.startsWith("tmdb:") }?.split(':')?.getOrNull(1)?.let { "tmdb:$it" }
        val first = listOfNotNull(id.takeIf { mediaServers.isNativeId(it) }, imdb, tmdbIdString)
        val updated = if (first.isEmpty()) 0 else mediaServers.setFavoriteAny(first, isShow, favorite)
        if (updated > 0) return updated
        val converted = listOfNotNull(
            if (imdb == null) tmdbIdString?.let { imdbVideoIdFromTmdb(type, it) } else null,
            if (tmdbIdString == null) imdb?.let { tmdbVideoIdFromImdb(type, it) } else null
        )
        return if (converted.isEmpty()) 0 else mediaServers.setFavoriteAny(converted, isShow, favorite)
    }

    /** "tt…[:s:e]" → "tmdb:<id>[:s:e]" via TMDB's find endpoint. */
    private suspend fun tmdbVideoIdFromImdb(type: String, videoId: String?): String? {
        val parts = videoId?.split(':') ?: return null
        val imdb = parts.firstOrNull()?.takeIf { it.startsWith("tt") } ?: return null
        val tmdbId = runCatching { tmdb.findByImdb(imdb, type != "movie") }.getOrNull() ?: return null
        return (listOf("tmdb", tmdbId.toString()) + parts.drop(1)).joinToString(":")
    }

    /** "tmdb:<id>[:s:e]" → "tt…[:s:e]" via TMDB's external ids, for media server lookups. */
    private suspend fun imdbVideoIdFromTmdb(type: String, videoId: String): String? {
        val parts = videoId.split(':')
        if (parts.firstOrNull() != "tmdb") return null
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val imdb = runCatching { tmdb.imdbIdFor(tmdbId, type != "movie") }.getOrNull() ?: return null
        return (listOf(imdb) + parts.drop(2)).joinToString(":")
    }

    /** Add-on subtitles (OpenSubtitles etc.) for a video; never throws, returns [] on failure. */
    @Throws(Exception::class)
    suspend fun subtitles(
        type: String,
        contentId: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long,
        filename: String?
    ): List<Subtitle> = subtitleRepository.subtitles(
        addonRepository.currentAddons, type, contentId, videoId, videoHash,
        videoSize.takeIf { it > 0 }, filename
    )

    // --- Progress & library ----------------------------------------------------------------

    fun observeContinueWatching(onChange: (List<WatchProgress>) -> Unit): CoreSubscription =
        // Only real changes reach the screen: a sync that brings nothing new doesn't redraw the row.
        observe(
            kotlinx.coroutines.flow.combine(watchProgress.entries, upNext, watched.watched) { _, _, _ -> continueWatching() }
                .distinctUntilChanged()
        ) { onChange(it) }

    /**
     * Continue Watching: titles in progress, plus "up next" episodes from trackers (Trakt watched
     * progress, Simkl's Watching list) for shows that have nothing in progress here, newest first.
     * Up next entries have no position (durationMs 0).
     */
    fun continueWatching(): List<WatchProgress> {
        // One card per title (its latest video); finished ones (90 %, or marked watched after most
        // of it played) leave, and their next episode shows as up next instead.
        val inProgress = watchProgress.entries.value
            .groupBy { it.contentId }
            .mapNotNull { (_, list) -> effective(list.maxByOrNull { it.updatedAtMs }) }
            .filter { it.isResumable }
            .sortedByDescending { it.updatedAtMs }
        val busy = inProgress.map { it.contentId }.toSet()
        val now = currentTimeMillis()
        val hideUnreleased = settings.hideUnreleasedUpNext
        // With the separate Upcoming row, unaired episodes stay (the row shows them) even when hidden here.
        val hideUnaired = hideUnreleased && !settings.separateUpcomingRow
        val next = upNext.value.filter {
            it.contentId !in busy && upNextKey(it) !in dismissedUpNext && !watched.isWatched(it.videoId) &&
                !(hideUnaired && (it.releasedAtMs ?: 0L) > now)
        }
        return (inProgress + next).sortedByDescending { it.updatedAtMs }
    }

    fun isUpNext(progress: WatchProgress): Boolean = progress.durationMs <= 0L

    /**
     * Season tab posters (Android Layout > Details "Season Posters"): one URL per entry of
     * [seasons], a season without its own poster getting the show's poster ("" when none), or
     * null — plain text tabs — when the setting is off or no season has a poster.
     */
    fun seasonPosterTabs(meta: Meta, seasons: List<Int>): List<String>? {
        if (!settings.detailSeasonPosters) return null
        val posters = seasons.map { meta.seasonPoster(it) }
        if (posters.all { it == null }) return null
        return posters.map { it ?: meta.poster.orEmpty() }
    }

    /** An up next episode that hasn't aired yet (its air date is known and in the future). */
    fun isUpcoming(progress: WatchProgress): Boolean =
        isUpNext(progress) && (progress.releasedAtMs ?: 0L) > currentTimeMillis()

    // --- Up next & dismissals ------------------------------------------------------------------

    private val progressJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val progressListSerializer =
        kotlinx.serialization.builtins.ListSerializer(WatchProgress.serializer())
    // "upcoming" last: on a tie the trackers' own entry for a show wins.
    private val upNextSources = listOf("simkl", "trakt", "mediaserver", "local", UPCOMING_SOURCE)
    /**
     * Air dates of up next episodes whose tracker gives none (Jellyfin / AIOMetadata, Simkl):
     * upNextKey → epoch ms, looked up once in the show's episode list and kept on the device.
     */
    private val airDates: MutableMap<String, Long> =
        store.getString("upnext.airdates")?.split('\n')?.mapNotNull { line ->
            val cut = line.lastIndexOf('|').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(cut + 1).toLongOrNull()?.let { line.substring(0, cut) to it }
        }?.toMap()?.toMutableMap() ?: mutableMapOf()
    /** Looked up this run without a date; asked again next launch. */
    private val airDateMisses = mutableSetOf<String>()
    private val airDateLock = kotlinx.coroutines.sync.Mutex()
    /** A refresh was asked for while one was running: run again when it ends (new up next entries). */
    private var airDateRerun = false
    private val upNext = kotlinx.coroutines.flow.MutableStateFlow(loadUpNext())
    private var dismissedUpNext: Set<String> =
        store.getString("upnext.dismissed")?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    /** contentId → time it was removed from Continue Watching; older tracker entries stay hidden. */
    private var dismissedProgress: Map<String, Long> =
        store.getString("progress.dismissed")?.split('\n')?.mapNotNull { line ->
            val (id, time) = line.split('|').takeIf { it.size == 2 } ?: return@mapNotNull null
            time.toLongOrNull()?.let { id to it }
        }?.toMap().orEmpty()

    private fun upNextKey(p: WatchProgress) = "${p.contentId}|${p.videoId}"

    private fun storedUpNext(source: String): List<WatchProgress> =
        store.getString("upnext.$source")?.let { runCatching { progressJson.decodeFromString(progressListSerializer, it) }.getOrNull() }
            .orEmpty()

    /** All trackers' up next, one card per show (the most recently watched source wins). */
    private fun loadUpNext(): List<WatchProgress> =
        upNextSources.flatMap(::storedUpNext)
            .groupBy { it.contentId }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.updatedAtMs } }
            .map { p -> if (p.releasedAtMs != null) p else airDates[upNextKey(p)]?.let { p.copy(releasedAtMs = it) } ?: p }

    /**
     * Fills in air dates the trackers don't give, from each show's episode list (one lookup per
     * show, then kept), so unaired episodes can go to the Upcoming row and show "Airs …".
     * Several shows are looked up at once and the row updates as dates come in. A lookup that
     * fails (add-on slow or down) is tried again on the next refresh; only a show whose episode
     * list really has no date waits for the next launch. One refresh runs at a time.
     */
    internal suspend fun refreshAirDates() {
        if (!airDateLock.tryLock()) {
            airDateRerun = true
            return
        }
        try {
            var retries = 0
            do {
                airDateRerun = false
                // Shows whose lookup failed are tried again shortly (twice), not only at the next sync.
                if (lookUpAirDates() && retries++ < AIR_DATE_RETRIES) {
                    kotlinx.coroutines.delay(AIR_DATE_RETRY_DELAY_MS)
                    airDateRerun = true
                }
            } while (airDateRerun)
        } finally {
            airDateLock.unlock()
        }
    }

    /** Returns true when some lookups failed (worth another try). */
    private suspend fun lookUpAirDates(): Boolean {
        val missing = upNext.value
            .filter { it.releasedAtMs == null && it.episode != null && upNextKey(it) !in airDateMisses }
            .distinctBy { it.contentId }
            .take(MAX_AIR_DATE_LOOKUPS)
        if (missing.isEmpty()) return false
        var failed = false
        for (batch in missing.chunked(AIR_DATE_PARALLEL)) {
            val metas = kotlinx.coroutines.coroutineScope {
                batch.map { show ->
                    async {
                        show to try {
                            meta(show.contentType, show.contentId, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
            var found = false
            for ((show, meta) in metas) {
                // Lookup failed: no verdict, so it's asked again next time.
                if (meta == null) { failed = true; continue }
                for (entry in upNext.value.filter { it.contentId == show.contentId && it.releasedAtMs == null }) {
                    val video = meta.videos.firstOrNull { it.id == entry.videoId }
                        ?: meta.videos.firstOrNull { it.episode == entry.episode && (entry.season == null || it.season == entry.season) }
                    val released = video?.released?.let { com.nuvio.shared.util.parseReleaseEpochMs(it) }
                    if (released != null) {
                        airDates[upNextKey(entry)] = released
                        found = true
                    } else {
                        airDateMisses += upNextKey(entry)
                    }
                }
            }
            if (found) saveAirDates()
        }
        return failed
    }

    /** Keeps the dates still in use and shows them (the Upcoming row updates). */
    private fun saveAirDates() {
        val keys = upNextSources.flatMap(::storedUpNext).map(::upNextKey).toSet()
        airDates.keys.retainAll(keys)
        store.putString("upnext.airdates", airDates.entries.joinToString("\n") { "${it.key}|${it.value}" })
        upNext.value = loadUpNext()
    }

    // --- Upcoming: unaired next episodes of caught-up shows ------------------------------------
    // Trackers' up next only lists aired episodes, so once every aired episode of a show is
    // watched it drops out. As on Android, the episode after the furthest one watched is then
    // looked up in the show's episode list; if it hasn't aired yet it goes to the Upcoming row.

    /** Per source ("trakt", "mediaserver"): the furthest episode watched of each show. */
    private var lastWatchedBySource: Map<String, List<WatchProgress>> = emptyMap()
    /** Simkl's next episode of shows with every aired episode watched. */
    private var simklCaughtUp: List<WatchProgress> = emptyList()
    private val upcomingLock = kotlinx.coroutines.sync.Mutex()
    private var upcomingRerun = false

    /**
     * Kept on the device, per show: the furthest episode finished or marked watched in the app
     * (and when), so the Upcoming row works without any tracker. Marking an episode watched
     * drops its progress entry, so progress alone can't tell how far a show was watched.
     */
    private var localSeeds: List<WatchProgress> =
        store.getString(LOCAL_SEEDS_KEY)?.let { runCatching { progressJson.decodeFromString(progressListSerializer, it) }.getOrNull() }
            .orEmpty()

    private fun saveLocalSeeds(list: List<WatchProgress>) {
        localSeeds = list.sortedByDescending { it.updatedAtMs }.take(MAX_LOCAL_SEEDS)
        store.putString(LOCAL_SEEDS_KEY, progressJson.encodeToString(progressListSerializer, localSeeds))
    }

    /** An episode was finished or marked watched here: the show's furthest point moves on if it's further. */
    private fun recordWatchedSeed(seed: WatchProgress) {
        val season = seed.season ?: return
        val episode = seed.episode ?: return
        if (season <= 0 || seed.contentId.isBlank()) return
        val current = localSeeds.firstOrNull { it.contentId == seed.contentId }
        val further = current == null || season > (current.season ?: 0) ||
            (season == current.season && episode >= (current.episode ?: 0))
        // Watching an earlier episode again keeps the furthest point, but counts as activity.
        val updated = if (further) seed else current!!.copy(updatedAtMs = seed.updatedAtMs)
        saveLocalSeeds(listOf(updated) + localSeeds.filterNot { it.contentId == seed.contentId })
    }

    /**
     * Unwatched: an episode at or before the show's furthest point means it isn't caught up any
     * more, so the show leaves the Upcoming row (it comes back when watched again).
     */
    private fun forgetWatchedSeed(contentId: String, season: Int?, episode: Int?) {
        val current = localSeeds.firstOrNull { it.contentId == contentId }
        val reaches = current == null || season == null || episode == null ||
            season < (current.season ?: 0) || (season == current.season && episode <= (current.episode ?: 0))
        if (!reaches) return
        if (current != null) saveLocalSeeds(localSeeds.filterNot { it.contentId == contentId })
        val upcoming = storedUpNext(UPCOMING_SOURCE)
        if (upcoming.any { it.contentId == contentId }) setUpNext(UPCOMING_SOURCE, upcoming.filterNot { it.contentId == contentId })
    }

    private fun episodeSeed(meta: Meta, video: Video, at: Long) = WatchProgress(
        contentId = meta.id, contentType = meta.apiType, videoId = video.id, name = meta.name,
        poster = meta.poster, background = meta.backdropUrl, season = video.season, episode = video.episode,
        positionMs = 0, durationMs = 0, updatedAtMs = at
    )

    private class UpcomingSeed(val progress: WatchProgress, val inclusive: Boolean)

    /** Looks up the Upcoming row's episodes again (one run at a time; a request meanwhile reruns it). */
    internal suspend fun refreshUpcoming() {
        if (!upcomingLock.tryLock()) {
            upcomingRerun = true
            return
        }
        try {
            do {
                upcomingRerun = false
                lookUpUpcoming()
            } while (upcomingRerun)
        } finally {
            upcomingLock.unlock()
        }
    }

    private suspend fun lookUpUpcoming() {
        val now = currentTimeMillis()
        val oldest = now - UPCOMING_SEED_WINDOW_MS
        val progress = watchProgress.entries.value
        // Shows with an aired next episode or something in progress aren't Upcoming material.
        val covered = upNextSources.filter { it != UPCOMING_SOURCE }.flatMap(::storedUpNext).map { it.contentId }.toSet() +
            progress.filter { it.isResumable }.map { it.contentId }
        // Episodes finished here (and progress synced from the Nuvio account).
        val local = progress.filter { it.isCompleted && it.season != null && it.episode != null && isSeriesLikeType(it.contentType) } +
            localSeeds
        val seeds = (lastWatchedBySource.values.flatten() + local).map { UpcomingSeed(it, inclusive = false) } +
            simklCaughtUp.map { UpcomingSeed(it, inclusive = true) }
        val chosen = seeds
            .filter { seed ->
                val p = seed.progress
                p.contentId.isNotBlank() && p.contentId !in covered && p.season != null && p.season != 0 &&
                    p.episode != null && (p.updatedAtMs >= oldest || p.updatedAtMs <= 1L)
            }
            .groupBy { it.progress.contentId }
            .mapNotNull { (_, list) ->
                // The furthest point reached; a named next episode counts one past its number.
                list.maxWithOrNull(
                    compareBy<UpcomingSeed>(
                        { it.progress.season ?: 0 },
                        { (it.progress.episode ?: 0) + if (it.inclusive) 0 else 1 },
                        { it.progress.updatedAtMs }
                    )
                )
            }
            .sortedByDescending { it.progress.updatedAtMs }
            .take(MAX_UPCOMING_LOOKUPS)
        val previous = storedUpNext(UPCOMING_SOURCE).associateBy { it.contentId }
        val result = mutableListOf<WatchProgress>()
        for (batch in chosen.chunked(AIR_DATE_PARALLEL)) {
            val metas = kotlinx.coroutines.coroutineScope {
                batch.map { seed ->
                    async {
                        seed to try {
                            meta(seed.progress.contentType, seed.progress.contentId, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
            for ((seed, meta) in metas) {
                if (meta == null) {
                    // Lookup failed: keep what was found before, while it's still ahead.
                    previous[seed.progress.contentId]?.takeIf { (it.releasedAtMs ?: 0L) > now }?.let { result += it }
                    continue
                }
                upcomingEntry(meta, seed.progress, seed.inclusive, now)?.let { result += it }
            }
        }
        setUpNext(UPCOMING_SOURCE, result)
    }

    /** The Upcoming row's card for a show, from its episode list and where it was left. */
    private fun upcomingEntry(meta: Meta, seed: WatchProgress, inclusive: Boolean, nowMs: Long): WatchProgress? {
        val season = seed.season ?: return null
        val episode = seed.episode ?: return null
        val (video, released) = upcomingEpisodeAfter(meta.videos, season, episode, seed.videoId, inclusive, nowMs)
            ?: return null
        return WatchProgress(
            contentId = seed.contentId,
            contentType = seed.contentType,
            videoId = video.id,
            name = meta.name.ifBlank { seed.name },
            poster = seed.poster ?: meta.poster,
            background = meta.backdropUrl ?: seed.background,
            logo = meta.logo,
            season = video.season,
            episode = video.episode,
            episodeTitle = video.title,
            positionMs = 0,
            durationMs = 0,
            updatedAtMs = seed.updatedAtMs,
            releasedAtMs = released,
            thumbnail = video.thumbnail
        )
    }

    /**
     * Up next without a tracker (Android shows it too): when an episode is finished here, the
     * following one (first not yet watched) waits in Continue Watching as "Next: S1 E5".
     */
    private fun queueNextEpisode(meta: Meta, video: Video?) {
        if (video == null || !meta.isSeriesLike) return
        recordWatchedSeed(episodeSeed(meta, video, currentTimeMillis()))
        val episodes = meta.watchableEpisodes()
        val index = episodes.indexOfFirst { it.id == video.id }
        if (index < 0) return
        val next = episodes.drop(index + 1).firstOrNull { !watched.isWatched(watchedKey(meta, it)) } ?: run {
            setUpNext("local", storedUpNext("local").filterNot { it.contentId == meta.id })
            // Caught up: the next episode to air goes to the Upcoming row right away.
            val now = currentTimeMillis()
            val seed = episodeSeed(meta, video, now)
            val others = storedUpNext(UPCOMING_SOURCE).filterNot { it.contentId == meta.id }
            setUpNext(UPCOMING_SOURCE, listOfNotNull(upcomingEntry(meta, seed, inclusive = false, nowMs = now)) + others)
            return
        }
        val entry = WatchProgress(
            contentId = meta.id,
            contentType = meta.apiType,
            videoId = next.id,
            name = meta.name,
            poster = meta.poster,
            background = meta.backdropUrl,
            logo = meta.logo,
            season = next.season,
            episode = next.episode,
            episodeTitle = next.title,
            positionMs = 0,
            durationMs = 0,
            updatedAtMs = currentTimeMillis(),
            releasedAtMs = com.nuvio.shared.util.parseReleaseEpochMs(next.released),
            thumbnail = next.thumbnail
        )
        setUpNext("local", (listOf(entry) + storedUpNext("local").filterNot { it.contentId == meta.id }).take(100))
    }

    /** Replaces one tracker's up next list (nothing is written when it is unchanged). */
    private fun setUpNext(source: String, list: List<WatchProgress>) {
        val encoded = progressJson.encodeToString(progressListSerializer, list)
        if (store.getString("upnext.$source") == encoded) return
        store.putString("upnext.$source", encoded)
        upNext.value = loadUpNext()
    }

    private fun dismissFromContinueWatching(contentId: String) {
        val keys = upNext.value.filter { it.contentId == contentId }.map(::upNextKey)
        if (keys.isNotEmpty()) {
            dismissedUpNext = dismissedUpNext + keys
            store.putString("upnext.dismissed", dismissedUpNext.joinToString("\n"))
        }
        dismissedProgress = dismissedProgress + (contentId to currentTimeMillis())
        store.putString("progress.dismissed", dismissedProgress.entries.joinToString("\n") { "${it.key}|${it.value}" })
        upNext.value = upNext.value.filterNot { it.contentId == contentId }
    }

    /** Tracker playback entry → local progress, unless older than local or than a dismissal. */
    private fun mergeRemoteProgress(remote: WatchProgress) {
        if (remote.updatedAtMs <= (dismissedProgress[remote.contentId] ?: 0L)) return
        val local = watchProgress.progressFor(remote.videoId)
        if (local == null || local.updatedAtMs < remote.updatedAtMs) {
            watchProgress.update(
                local?.let { remote.copy(durationMs = it.durationMs, positionMs = (it.durationMs * remote.fraction).toLong()) }
                    ?: remote
            )
        }
    }

    /**
     * Jellyfin / Emby Continue Watching, applied as a diff against what the servers reported last
     * time (kept on the device): new or changed entries are merged, entries the server dropped
     * (finished or removed there) leave Continue Watching unless they were watched here since,
     * and nothing is written when the server's list is unchanged. An unreachable server changes
     * nothing.
     */
    private suspend fun syncServerResume() {
        val current = mediaServers.resumeSnapshot() ?: return
        val previous = loadServerResume()
        val now = current.associate { it.videoId to it.updatedAtMs }
        val updates = current.filter { remote ->
            previous[remote.videoId] != remote.updatedAtMs &&
                remote.updatedAtMs > (dismissedProgress[remote.contentId] ?: 0L) &&
                (watchProgress.progressFor(remote.videoId)?.updatedAtMs ?: 0L) < remote.updatedAtMs
        }.map { remote ->
            watchProgress.progressFor(remote.videoId)?.let { local ->
                remote.copy(durationMs = local.durationMs, positionMs = (local.durationMs * remote.fraction).toLong())
            } ?: remote
        }
        val removed = previous.filterKeys { it !in now }.filter { (videoId, reportedAt) ->
            // Only what the server put there, and not watched here after the server's last report.
            val local = watchProgress.progressFor(videoId)
            local != null && local.updatedAtMs <= reportedAt
        }.keys
        watchProgress.apply(updates, removed)
        if (now != previous) {
            store.putString(SERVER_RESUME_KEY, now.entries.joinToString("\n") { "${it.key}|${it.value}" })
        }
    }

    private fun loadServerResume(): Map<String, Long> =
        store.getString(SERVER_RESUME_KEY)?.split('\n')?.mapNotNull { line ->
            val cut = line.lastIndexOf('|')
            if (cut <= 0) null else line.substring(0, cut) to (line.substring(cut + 1).toLongOrNull() ?: return@mapNotNull null)
        }?.toMap().orEmpty()

    fun progressFor(videoId: String): WatchProgress? = effective(watchProgress.progressFor(videoId))

    /**
     * An entry as the screens should see it: one marked watched after most of it was played (a
     * tracker's 80 %, or "Mark watched") counts as finished, so no "Resume" is offered for it.
     */
    private fun effective(progress: WatchProgress?): WatchProgress? {
        if (progress == null || progress.isCompleted || progress.durationMs <= 0) return progress
        if (progress.fraction < WatchProgress.WATCHED_FINISHED_FRACTION) return progress
        val watchedHere = watched.isWatched(progress.videoId) ||
            (progress.contentType == "movie" && watched.isWatched("movie:${progress.contentId}"))
        return if (watchedHere) progress.copy(positionMs = progress.durationMs) else progress
    }

    /** The subtitle choice remembered for a series / movie (the player's own JSON), per profile. */
    fun subtitleChoice(contentId: String): String? = store.getString("subs.choice.$contentId")

    fun setSubtitleChoice(contentId: String, choice: String?) = store.putString("subs.choice.$contentId", choice)

    fun latestProgressFor(contentId: String): WatchProgress? = effective(watchProgress.latestFor(contentId))

    fun saveProgress(
        meta: Meta,
        videoId: String,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        positionMs: Long,
        durationMs: Long
    ) {
        if (durationMs <= 0) return
        if (positionMs.toDouble() / durationMs >= WatchProgress.COMPLETED_THRESHOLD) {
            val video = meta.videos.firstOrNull { it.id == videoId }
            watched.mark(watchedKey(meta, video), true)
            queueNextEpisode(meta, video)
        }
        watchProgress.update(
            WatchProgress(
                contentId = meta.id,
                contentType = meta.apiType,
                videoId = videoId,
                name = meta.name,
                poster = meta.poster,
                background = meta.backdropUrl,
                logo = meta.logo,
                season = season,
                episode = episode,
                episodeTitle = episodeTitle,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAtMs = currentTimeMillis(),
                thumbnail = if (meta.isSeriesLike) meta.videos.firstOrNull { it.id == videoId }?.thumbnail else null
            )
        )
        // Jellyfin / Emby: a progress report at most every 30 s while playing.
        if (mediaServers.servers.isNotEmpty()) {
            val now = currentTimeMillis()
            if (now - (serverReportedAt[videoId] ?: 0L) >= 30_000L) {
                serverReportedAt[videoId] = now
                val video = meta.videos.firstOrNull { it.id == videoId }
                scope.launch { reportToServers(meta, video, videoId, "progress", positionMs) }
            }
        }
    }

    private val serverReportedAt = mutableMapOf<String, Long>()

    private suspend fun reportToServers(meta: Meta, video: Video?, videoId: String, event: String, positionMs: Long, paused: Boolean = false) {
        runCatching {
            mediaServers.reportPlayback(
                contentId = meta.id, imdbId = meta.imdbId, isShow = meta.isSeriesLike && video != null,
                videoId = videoId, season = video?.season, episode = video?.episode,
                event = event, positionMs = positionMs, isPaused = paused
            )
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun setPlayedOnServers(contentId: String, imdbId: String?, isShow: Boolean, videoId: String, season: Int?, episode: Int?, played: Boolean) {
        if (mediaServers.servers.isEmpty()) return
        scope.launch {
            runCatching { mediaServers.setPlayed(contentId, imdbId, isShow, videoId, season, episode, played) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    /** contentId → (video id / "season:episode") → episode still. */
    private val episodeThumbs = LinkedHashMap<String, Map<String, String>>()

    /**
     * The episode still for a Continue Watching / Up Next entry of a series: the one saved with the
     * progress, else looked up (once per show) in the show's meta. Null for movies or when the
     * add-on has no stills, so the card falls back to the show's artwork.
     */
    @Throws(Exception::class)
    suspend fun episodeThumbnail(progress: WatchProgress): String? {
        progress.thumbnail?.let { return it }
        if (progress.season == null && progress.episode == null) return null
        val thumbs = episodeThumbs[progress.contentId] ?: run {
            val meta = try {
                meta(progress.contentType, progress.contentId, null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            val map = buildMap {
                for (v in meta.videos) {
                    val thumb = v.thumbnail?.takeIf { it.isNotBlank() } ?: continue
                    put(v.id, thumb)
                    if (v.season != null && v.episode != null) put("${v.season}:${v.episode}", thumb)
                }
            }
            episodeThumbs[progress.contentId] = map
            if (episodeThumbs.size > 100) episodeThumbs.remove(episodeThumbs.keys.first())
            map
        }
        return thumbs[progress.videoId]
            ?: if (progress.season != null && progress.episode != null) thumbs["${progress.season}:${progress.episode}"] else null
    }

    fun removeProgress(contentId: String) {
        watchProgress.remove(contentId)
        dismissFromContinueWatching(contentId)
        // Also out of the Jellyfin servers' Continue Watching (their resume points are cleared).
        if (mediaServers.servers.any { it.kind == "jellyfin" }) {
            scope.launch {
                runCatching { mediaServers.removeFromResume(contentId) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    // --- Watched & trackers -----------------------------------------------------------------

    fun watchedKey(meta: Meta, video: Video?): String =
        if (video != null && meta.isSeriesLike) video.id else "movie:${meta.imdbId ?: meta.id}"

    fun isWatched(meta: Meta, video: Video?): Boolean = watched.isWatched(watchedKey(meta, video))

    fun observeWatched(onChange: (Set<String>) -> Unit): CoreSubscription = observe(watched.watched, onChange)

    /** Manual watched toggle (also pushed to Trakt / Simkl / AniList when marking watched). */
    fun setWatched(meta: Meta, video: Video?, isWatched: Boolean) {
        watched.mark(watchedKey(meta, video), isWatched)
        if (isWatched) {
            // Watched means finished: no "Resume" for it, and the next episode is up next.
            watchProgress.apply(emptyList(), setOf(video?.id ?: meta.defaultVideoId))
            queueNextEpisode(meta, video)
        }
        if (!isWatched && video != null && meta.isSeriesLike) forgetWatchedSeed(meta.id, video.season, video.episode)
        if (video != null) refreshSeriesWatched(meta)
        if (!meta.isSeriesLike || video != null) {
            setPlayedOnServers(meta.id, meta.imdbId, meta.isSeriesLike, video?.id ?: meta.defaultVideoId,
                video?.season, video?.episode, isWatched)
        }
        if (isWatched) {
            val media = TrackedMedia.from(meta, video)
            scope.launch {
                if (cloud.isSignedIn) runCatching {
                    cloud.pushWatched(meta.id, meta.apiType, meta.name, video?.season, video?.episode)
                }
                runCatching { trakt.markWatched(media) }
                runCatching { simkl.markWatched(media) }
                runCatching { mdbListTracker.markWatched(media) }
                runCatching { aniList.updateProgress(media, completedTitle = false) }
            }
        }
    }

    /**
     * Player events → scrobbling. [action] is "start", "pause" or "stop"; [progressPercent] 0–100.
     * A stop at ≥ 80 % (Trakt's own threshold) marks the item watched everywhere.
     */
    fun reportPlayback(meta: Meta, video: Video?, action: String, progressPercent: Double) {
        val media = TrackedMedia.from(meta, video)
        val scrobble = when (action) {
            "start" -> ScrobbleAction.START
            "pause" -> ScrobbleAction.PAUSE
            else -> ScrobbleAction.STOP
        }
        if (mediaServers.servers.isNotEmpty()) {
            val videoId = video?.id ?: meta.defaultVideoId
            val saved = watchProgress.progressFor(videoId)
            val positionMs = saved?.let { (it.durationMs * progressPercent / 100).toLong().takeIf { p -> p > 0 } ?: it.positionMs } ?: 0L
            val event = when (scrobble) {
                ScrobbleAction.START -> "start"
                ScrobbleAction.PAUSE -> "pause"
                else -> "stop"
            }
            serverReportedAt[videoId] = currentTimeMillis()
            scope.launch {
                reportToServers(meta, video, videoId, event, positionMs, paused = scrobble == ScrobbleAction.PAUSE)
                if (scrobble == ScrobbleAction.STOP && progressPercent >= 90.0) {
                    runCatching {
                        mediaServers.setPlayed(meta.id, meta.imdbId, meta.isSeriesLike && video != null, videoId,
                            video?.season, video?.episode, true)
                    }.onFailure { if (it is CancellationException) throw it }
                }
            }
        }
        scope.launch {
            runCatching { trakt.scrobble(media, scrobble, progressPercent) }
            runCatching { simkl.scrobble(media, scrobble, progressPercent) }
            runCatching { mdbListTracker.scrobble(media, scrobble, progressPercent) }
            if (scrobble == ScrobbleAction.STOP && cloud.isSignedIn) {
                runCatching { cloud.pushProgress(watchProgress.entries.value.filter { it.contentId == meta.id }.take(5)) }
            }
            if (scrobble == ScrobbleAction.STOP && progressPercent >= WatchProgress.COMPLETED_THRESHOLD * 100) {
                if (cloud.isSignedIn) runCatching { cloud.pushWatched(meta.id, meta.apiType, meta.name, video?.season, video?.episode) }
                watched.mark(watchedKey(meta, video), true)
                queueNextEpisode(meta, video)
                if (video != null) refreshSeriesWatched(meta)
                runCatching { aniList.updateProgress(media, completedTitle = false) }
            }
        }
    }

    /** Pulls Trakt history, playback progress and watchlist into local state. Returns an error text or null. */
    @Throws(Exception::class)
    suspend fun syncTrackers(): String? {
        var error: String? = null
        suspend fun sync(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (error == null) error = e.message ?: "$name sync failed"
            }
        }
        if (trakt.isConnected) sync("Trakt") {
            val state = trakt.watchedState()
            watched.addAll(state.keys)
            setTrackerCompleted("trakt", state.completedSeries)
            lastWatchedBySource = lastWatchedBySource + ("trakt" to state.lastWatchedEpisodes)
            trakt.playbackProgress().forEach(::mergeRemoteProgress)
            setUpNext("trakt", trakt.upNext())
            traktWatchlistCache = trakt.watchlist()
        }
        if (simkl.isConnected) sync("Simkl") {
            val state = simkl.completedItems()
            watched.addAll(state.keys + simkl.watchedEpisodeKeys())
            setTrackerCompleted("simkl", state.completedSeries)
            // Shows and anime: partly watched episodes, and the next episode of each show on the
            // Watching list (movies come through playback too).
            simkl.playbackProgress().forEach(::mergeRemoteProgress)
            setUpNext("simkl", simkl.upNext())
            simklCaughtUp = simkl.caughtUpNext
        } else {
            simklCaughtUp = emptyList()
            if (storedUpNext("simkl").isNotEmpty()) setUpNext("simkl", emptyList())
        }
        if (!trakt.isConnected) {
            lastWatchedBySource = lastWatchedBySource - "trakt"
            if (storedUpNext("trakt").isNotEmpty()) setUpNext("trakt", emptyList())
        }
        if (mdbListTracker.isConnected) sync("MDBList") {
            watched.addAll(mdbListTracker.watchedKeys())
            mdbListTracker.playbackProgress().forEach(::mergeRemoteProgress)
            // Re-checks which lists changed on MDBList; unchanged lists keep their cached titles.
            runCatching { mdbListTracker.lists(refresh = true) }.onFailure { if (it is CancellationException) throw it }
            mdbListWatchlistCache = mdbListTracker.watchlist()
        } else {
            mdbListWatchlistCache = emptyList()
        }
        if (aniList.isConnected) sync("AniList") {
            val state = aniList.completedItems()
            watched.addAll(state.keys)
            setTrackerCompleted("anilist", state.completedSeries)
        }
        // Jellyfin / Emby: what's played there counts as watched here, and its resume points join
        // Continue Watching (newest position wins, as with Trakt / Simkl).
        if (mediaServers.servers.isNotEmpty()) sync("Media server") {
            watched.addAll(mediaServers.playedKeys())
            syncServerResume()
            // Next Up: the next episode of each show being watched on the server.
            setUpNext("mediaserver", mediaServers.nextUp())
            lastWatchedBySource = lastWatchedBySource + ("mediaserver" to mediaServers.lastPlayedEpisodes())
        } else {
            lastWatchedBySource = lastWatchedBySource - "mediaserver"
            if (storedUpNext("mediaserver").isNotEmpty()) setUpNext("mediaserver", emptyList())
        }
        // In the background: the Upcoming row's episodes, then air dates for up next episodes the
        // trackers didn't date.
        scope.launch {
            runCatching { refreshUpcoming() }.onFailure { if (it is CancellationException) throw it }
            runCatching { refreshAirDates() }.onFailure { if (it is CancellationException) throw it }
        }
        return error
    }

    // --- Fully watched shows ------------------------------------------------------------------
    // Two sources, kept apart so neither erases the other: what each tracker reports as finished
    // (replaced on every successful sync of that tracker), and "series:" keys in the local watched
    // set, set when every aired episode is watched here (or the show is marked from a card).

    private val trackerNames = listOf("trakt", "simkl", "anilist")
    private var trackerCompleted: Set<String> = loadTrackerCompleted()

    private fun loadTrackerCompleted(): Set<String> =
        trackerNames.flatMap { name -> store.getString("trackers.completed.$name")?.split('\n').orEmpty() }
            .filter { it.isNotBlank() }.toSet()

    private fun setTrackerCompleted(tracker: String, keys: Set<String>) {
        store.putString("trackers.completed.$tracker", keys.joinToString("\n"))
        trackerCompleted = loadTrackerCompleted()
    }

    private fun forgetTrackerCompleted(keys: Set<String>) {
        trackerNames.forEach { name ->
            val stored = store.getString("trackers.completed.$name")?.split('\n').orEmpty().toSet()
            if (stored.any { it in keys }) store.putString("trackers.completed.$name", (stored - keys).joinToString("\n"))
        }
        trackerCompleted = loadTrackerCompleted()
    }

    private fun seriesKeys(id: String, imdbId: String?): Set<String> =
        setOfNotNull(seriesKey(id), imdbId?.substringBefore(':')?.takeIf { it.startsWith("tt") }?.let(::seriesKey))

    /** True when a tracker reports the show finished or every aired episode is watched here. */
    fun isSeriesFullyWatched(id: String, imdbId: String?): Boolean =
        seriesKeys(id, imdbId).any { it in trackerCompleted || watched.isWatched(it) }

    /**
     * Re-checks a show against its episode list: marks it fully watched when every aired
     * regular-season episode is watched, clears the local mark otherwise (e.g. a new episode
     * aired). Called when details load and after an episode is marked or finished.
     */
    fun refreshSeriesWatched(meta: Meta) {
        if (!meta.isSeriesLike) return
        val now = currentTimeMillis()
        val aired = meta.videos.filter {
            (it.season ?: 0) > 0 && it.episode != null && !isReleaseInFuture(it.released, now)
        }
        if (aired.isEmpty()) return
        val keys = seriesKeys(meta.id, meta.imdbId)
        val all = aired.all { watched.isWatched(watchedKey(meta, it)) }
        if (all) {
            if (keys.any { !watched.isWatched(it) }) watched.addAll(keys)
        } else if (keys.any { watched.isWatched(it) }) {
            watched.removeAll(keys)
        }
    }

    /** Last fetched Trakt watchlist (Library tab). */
    var traktWatchlistCache: List<MetaPreview> = emptyList()
        private set

    /** Last fetched MDBList watchlist (Library tab). */
    var mdbListWatchlistCache: List<MetaPreview> = emptyList()
        private set

    fun observeLibrary(onChange: (List<SavedItem>) -> Unit): CoreSubscription = observe(library.items, onChange)

    fun isInLibrary(id: String): Boolean = library.contains(id)

    /** Returns true when the title is now saved (mirrored to the Trakt watchlist when connected). */
    fun toggleLibrary(meta: Meta): Boolean = toggleLibraryItem(
        SavedItem(
            id = meta.id,
            type = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            background = meta.backdropUrl,
            posterShape = meta.posterShape.name.lowercase(),
            savedAtMs = currentTimeMillis()
        ),
        imdb = meta.imdbId ?: meta.id.takeIf { it.startsWith("tt") },
        isShow = meta.isSeriesLike
    )

    /** Library toggle from a card's long-press menu (no full meta needed). */
    fun toggleLibraryPreview(preview: MetaPreview): Boolean = toggleLibraryItem(
        SavedItem(
            id = preview.id,
            type = preview.apiType,
            name = preview.name,
            poster = preview.poster,
            background = preview.backdropUrl,
            posterShape = preview.posterShape.name.lowercase(),
            savedAtMs = currentTimeMillis()
        ),
        imdb = preview.imdbId ?: preview.id.takeIf { it.startsWith("tt") },
        isShow = com.nuvio.shared.model.isSeriesLikeType(preview.apiType)
    )

    private fun toggleLibraryItem(item: SavedItem, imdb: String?, isShow: Boolean): Boolean =
        library.toggle(item).also { saved ->
            if (trakt.isConnected && imdb != null) {
                scope.launch { runCatching { trakt.setShowInWatchlist(imdb, isShow, saved) } }
            }
            if (mdbListTracker.isConnected) {
                val tmdb = item.id.takeIf { it.startsWith("tmdb:") }?.split(':')?.getOrNull(1)?.toIntOrNull()
                scope.launch {
                    runCatching { mdbListTracker.setInWatchlist(imdb?.substringBefore(':'), tmdb, isShow, saved) }
                    if (!saved) mdbListWatchlistCache = mdbListWatchlistCache.filterNot { it.id == item.id || (imdb != null && it.imdbId == imdb) }
                }
            }
            if (cloud.isSignedIn) scope.launch {
                runCatching {
                    if (saved) cloud.pushLibrary(library.items.value.filter { it.id == item.id })
                    else cloud.deleteLibrary(item.id, item.type)
                }
            }
        }

    /** Watched state for a card: the movie, or for a show whether it was marked fully watched. */
    fun isPreviewWatched(preview: MetaPreview): Boolean =
        if (isSeriesLikeType(preview.apiType)) isSeriesFullyWatched(preview.id, preview.imdbId)
        else watched.isWatched("movie:${preview.imdbId ?: preview.id}") || watched.isWatched("movie:${preview.id}")

    private fun seriesKey(id: String) = "series:$id"

    /**
     * Card menu "Mark as watched" for a show (Android PosterOptionsController.toggleSeriesWatched):
     * loads the episode list and marks / unmarks every aired episode (specials excluded), here and
     * on Trakt, Simkl and the Nuvio account. Throws when the add-on returns no episodes.
     */
    @Throws(Exception::class)
    suspend fun setSeriesWatched(preview: MetaPreview, isWatched: Boolean) {
        val meta = meta(preview.apiType, preview.id, preview.sourceAddonBaseUrl)
        val episodes = meta.videos.filter { (it.season ?: 0) > 0 && it.episode != null }
        if (episodes.isEmpty() && isWatched) error("No episodes found for ${preview.name}")
        val episodeKeys = episodes.map { watchedKey(meta, it) }
        val showKeys = seriesKeys(preview.id, preview.imdbId) + seriesKeys(meta.id, meta.imdbId)
        if (isWatched) {
            watched.addAll(episodeKeys + showKeys)
            // Caught up to the latest aired episode: the next one to air goes to the Upcoming row.
            val now = currentTimeMillis()
            episodes.filter { !com.nuvio.shared.util.isReleaseInFuture(it.released) }
                .maxWithOrNull(compareBy<Video>({ it.season }, { it.episode }))
                ?.let { last ->
                    val seed = episodeSeed(meta, last, now)
                    recordWatchedSeed(seed)
                    val others = storedUpNext(UPCOMING_SOURCE).filterNot { it.contentId == meta.id }
                    setUpNext(UPCOMING_SOURCE, listOfNotNull(upcomingEntry(meta, seed, inclusive = false, nowMs = now)) + others)
                }
        } else {
            forgetTrackerCompleted(showKeys)
            watched.removeAll(episodeKeys + showKeys)
            forgetWatchedSeed(meta.id, null, null)
            if (preview.id != meta.id) forgetWatchedSeed(preview.id, null, null)
        }
        val numbers = episodes.map { it.season!! to it.episode!! }
        val show = TrackedMedia.from(meta, null)
        scope.launch {
            if (mediaServers.servers.isNotEmpty()) runCatching { mediaServers.setSeriesPlayed(meta.id, meta.imdbId ?: preview.imdbId, isWatched) }
                .onFailure { if (it is CancellationException) throw it }
            runCatching { trakt.markEpisodesWatched(show, numbers, remove = !isWatched) }
            runCatching { simkl.markEpisodesWatched(show, numbers, remove = !isWatched) }
            runCatching { mdbListTracker.markEpisodesWatched(show, numbers, remove = !isWatched) }
            if (cloud.isSignedIn) runCatching {
                if (isWatched) cloud.pushWatchedEpisodes(meta.id, meta.apiType, meta.name, numbers)
                else cloud.deleteWatched(meta.id, numbers)
            }
        }
    }

    fun setPreviewWatched(preview: MetaPreview, isWatched: Boolean) {
        watched.mark("movie:${preview.imdbId ?: preview.id}", isWatched)
        setPlayedOnServers(preview.id, preview.imdbId, false, preview.id, null, null, isWatched)
        if (!isWatched) return
        val media = TrackedMedia(
            isEpisode = false,
            imdbId = (preview.imdbId ?: preview.id).takeIf { it.startsWith("tt") }?.substringBefore(':'),
            tmdbId = preview.id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.toIntOrNull(),
            kitsuId = null,
            title = preview.name,
            year = preview.releaseInfo?.take(4)?.toIntOrNull(),
            season = null,
            episode = null
        )
        scope.launch {
            runCatching { trakt.markWatched(media) }
            runCatching { simkl.markWatched(media) }
            runCatching { mdbListTracker.markWatched(media) }
            if (cloud.isSignedIn) runCatching { cloud.pushWatched(preview.id, preview.apiType, preview.name, null, null) }
        }
    }

    /**
     * Continue Watching "Mark as watched": marks the movie / episode watched everywhere and drops
     * it from Continue Watching.
     */
    fun markProgressWatched(progress: WatchProgress) {
        // Kitsu anime episodes have no season but are still episodes.
        val isEpisode = progress.episode != null && progress.contentType != "movie"
        watched.mark(if (isEpisode) progress.videoId else "movie:${progress.contentId}", true)
        setPlayedOnServers(progress.contentId, null, isEpisode, progress.videoId, progress.season, progress.episode, true)
        watchProgress.remove(progress.contentId)
        dismissFromContinueWatching(progress.contentId)
        if (isEpisode && progress.season != null) {
            recordWatchedSeed(progress.copy(positionMs = 0, durationMs = 0, releasedAtMs = null, updatedAtMs = currentTimeMillis()))
            // Caught up now? Its next episode may go to the Upcoming row.
            scope.launch { runCatching { refreshUpcoming() }.onFailure { if (it is CancellationException) throw it } }
        }
        val media = TrackedMedia(
            isEpisode = isEpisode,
            imdbId = progress.contentId.takeIf { it.startsWith("tt") }?.substringBefore(':'),
            tmdbId = null,
            kitsuId = progress.contentId.takeIf { it.startsWith("kitsu:") }?.split(':')?.getOrNull(1),
            title = progress.name,
            year = null,
            season = progress.season,
            episode = progress.episode
        )
        scope.launch {
            runCatching { trakt.markWatched(media) }
            runCatching { simkl.markWatched(media) }
            runCatching { mdbListTracker.markWatched(media) }
            if (cloud.isSignedIn) runCatching {
                cloud.pushWatched(progress.contentId, progress.contentType, progress.name, progress.season, progress.episode)
            }
        }
    }

    /** Preview used to open the detail screen from a continue-watching card. */
    fun previewFor(progress: WatchProgress): MetaPreview = SavedItem(
        id = progress.contentId,
        type = progress.contentType,
        name = progress.name,
        poster = progress.poster,
        background = progress.background,
        savedAtMs = progress.updatedAtMs
    ).toPreview()

    /** Intro / recap / credits segments for the skip button (empty when disabled or unknown). */
    @Throws(Exception::class)
    suspend fun skipSegments(meta: Meta, video: Video?, durationSeconds: Double): List<com.nuvio.shared.tracking.SkipSegment> {
        if (!settings.skipIntroEnabled) return emptyList()
        return skipSegments.segments(skipQuery(meta, video), durationSeconds)
    }

    /**
     * Ids for the skip lookup, as Android fetchSkipIntervals: "kitsu:<id>:<ep>" and "mal:<id>:<ep>"
     * video ids give the anime entry and its own episode number; IMDb comes from the meta, the
     * video id ("tt…:s:e") or, for TMDB-only shows, TMDB's external ids.
     */
    private suspend fun skipQuery(meta: Meta, video: Video?): com.nuvio.shared.tracking.SkipQuery {
        val parts = (video?.id ?: meta.id).split(':')
        val isMovie = video == null || !meta.isSeriesLike
        val animeSource = parts.firstOrNull()?.takeIf { it == "kitsu" || it == "mal" }
        val animeEpisode = if (animeSource != null) parts.getOrNull(2)?.toIntOrNull() ?: video?.episode else video?.episode
        var imdb = (meta.imdbId ?: parts.firstOrNull { it.startsWith("tt") } ?: meta.id.takeIf { it.startsWith("tt") })
            ?.substringBefore(':')
        if (imdb == null && parts.firstOrNull() == "tmdb") {
            imdb = parts.getOrNull(1)?.toIntOrNull()?.let { runCatching { tmdb.imdbIdFor(it, !isMovie) }.getOrNull() }
        }
        return com.nuvio.shared.tracking.SkipQuery(
            isMovie = isMovie,
            imdbId = imdb,
            season = video?.season,
            episode = video?.episode,
            kitsuId = if (animeSource == "kitsu") parts.getOrNull(1) else null,
            malId = if (animeSource == "mal") parts.getOrNull(1) else null,
            animeEpisode = animeEpisode
        )
    }

    /**
     * A directly playable trailer URL via the trailer API (TRAILER_API_URL, as on Android), trying
     * the add-on's YouTube ids, then TMDB's. Null when none resolves.
     */
    @Throws(Exception::class)
    suspend fun trailerUrl(meta: Meta, tmdbKeys: List<String>): String? {
        val api = config.trailerApiUrl.trim().trimEnd('/')
        if (api.isEmpty()) return null
        for (key in (meta.trailerYtIds + tmdbKeys).distinct().take(4)) {
            val url = "$api/trailer?youtube_url=${com.nuvio.shared.util.encodeUrlComponent("https://www.youtube.com/watch?v=$key")}" +
                "&title=${com.nuvio.shared.util.encodeUrlComponent(meta.name)}" +
                (meta.releaseInfo?.take(4)?.let { "&year=$it" } ?: "")
            val resolved = runCatching {
                val body = com.nuvio.shared.data.AddonJson.parseToJsonElement(http.get(url).bodyAsText())
                (body as? kotlinx.serialization.json.JsonObject)?.get("url")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }.getOrNull()
            if (!resolved.isNullOrBlank() && resolved != "null") return resolved
        }
        return null
    }

    val hasTrailerApi: Boolean get() = config.trailerApiUrl.isNotBlank()

    // --- Release calendar -------------------------------------------------------------------

    /**
     * Episodes airing from [daysBack] days ago to [daysAhead] days ahead for shows in the library,
     * Continue Watching and the Trakt watchlist (port of the Android calendar screen's data).
     */
    @Throws(Exception::class)
    suspend fun releaseCalendar(daysBack: Int, daysAhead: Int): List<CalendarEntry> {
        val now = currentTimeMillis()
        val from = now - daysBack * 86_400_000L
        val to = now + daysAhead * 86_400_000L
        val shows = LinkedHashMap<String, String>() // id → type
        library.items.value.filter { com.nuvio.shared.model.isSeriesLikeType(it.type) }.forEach { shows[it.id] = it.type }
        watchProgress.entries.value.filter { com.nuvio.shared.model.isSeriesLikeType(it.contentType) }.forEach { shows[it.contentId] = it.contentType }
        (traktWatchlistCache + mdbListWatchlistCache).filter { it.type == com.nuvio.shared.model.ContentType.SERIES }.forEach { shows[it.id] = "series" }
        val entries = mutableListOf<CalendarEntry>()
        for ((id, type) in shows.entries.take(60)) {
            val meta = try {
                meta(type, id, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            meta.videos.forEach { video ->
                val released = com.nuvio.shared.util.parseReleaseEpochMs(video.released) ?: return@forEach
                if (released in from..to && (video.season ?: 0) > 0) {
                    entries += CalendarEntry(meta.toPreview(), video, released)
                }
            }
        }
        return entries.sortedBy { it.releaseEpochMs }
    }

    // --- Live TV ----------------------------------------------------------------------------

    fun observeLiveTv(onChange: (com.nuvio.shared.livetv.LiveTvState) -> Unit): CoreSubscription = observe(liveTv.state, onChange)

    @Throws(Exception::class)
    suspend fun loadLiveTv(force: Boolean) =
        // Heavy work (downloads, guide parsing, cache) moves off it inside; the channel list and
        // guide are swapped on the main thread, where the screen reads them.
        kotlinx.coroutines.withContext(mainDispatcher) { liveTv.load(force) }

    /** Placeholder meta so live channels can go through the same player (no progress is saved). */
    fun liveTvMeta(channel: com.nuvio.shared.livetv.LiveTvChannel): Meta = Meta(
        id = "livetv:${channel.id}",
        type = com.nuvio.shared.model.ContentType.CHANNEL,
        name = channel.name,
        poster = channel.logoUrl,
        posterShape = com.nuvio.shared.model.PosterShape.SQUARE,
        background = null,
        logo = channel.logoUrl,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    /**
     * Full preview for a Continue Watching entry (logo, description, genres, year…) from the
     * title's add-on meta; progress only stores name and artwork. Falls back to the basic preview.
     */
    @Throws(Exception::class)
    suspend fun continueWatchingPreview(progress: WatchProgress): MetaPreview {
        val basic = previewFor(progress)
        val meta = metaRepository.cached(progress.contentType, progress.contentId)
            ?: try {
                meta(progress.contentType, progress.contentId, null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return basic
            }
        val full = meta.toPreview()
        return full.copy(
            background = full.background ?: basic.background,
            poster = full.poster ?: basic.poster,
            logo = full.logo ?: progress.logo
        )
    }

    fun close() = scope.cancel()

    // --- Nuvio collections (imported from JSON, read-only, kept on this device only) -------

    private val nuvioCollectionsState = kotlinx.coroutines.flow.MutableStateFlow(
        store.getString(NUVIO_COLLECTIONS_KEY)?.let {
            runCatching { com.nuvio.shared.collections.NuvioCollectionsJson.parse(it) }.getOrNull()
        }.orEmpty()
    )

    /** Imported Nuvio collections, in their order (pinned ones first on Home). */
    fun nuvioCollections(): List<com.nuvio.shared.collections.NuvioCollection> = nuvioCollectionsState.value

    fun observeNuvioCollections(onChange: (List<com.nuvio.shared.collections.NuvioCollection>) -> Unit): CoreSubscription =
        observe(nuvioCollectionsState, onChange)

    /**
     * Replaces the imported collections with those in [input]: collections JSON exported from the
     * Android app or its phone page, or a link to such a file. Returns how many were imported.
     * Nothing is synced anywhere.
     */
    @Throws(Exception::class)
    suspend fun importNuvioCollections(input: String): Int {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Paste collections JSON or a link to it")
        val text = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val response = http.get(trimmed)
            if (!response.status.isSuccess()) throw IllegalStateException("Couldn't download the collections (HTTP ${response.status.value})")
            response.bodyAsText()
        } else trimmed
        val parsed = com.nuvio.shared.collections.NuvioCollectionsJson.parse(text)
        if (parsed.isEmpty()) throw IllegalArgumentException("No collections found in the JSON")
        store.putString(NUVIO_COLLECTIONS_KEY, com.nuvio.shared.collections.NuvioCollectionsJson.encode(parsed))
        nuvioCollectionsState.value = parsed
        return parsed.size
    }

    fun removeNuvioCollections() {
        store.putString(NUVIO_COLLECTIONS_KEY, null)
        nuvioCollectionsState.value = emptyList()
    }

    /** The add-on catalog a collection source points at, among the enabled add-ons. */
    private fun collectionSlot(source: com.nuvio.shared.collections.NuvioCollectionSource): CatalogSlot? {
        val addons = addonRepository.currentAddons.filter { it.enabled }
        val addon = addons.firstOrNull { it.id == source.addonId } ?: return null
        val catalog = addon.catalogs.firstOrNull { it.id == source.catalogId && it.apiType == source.type }
            ?: addon.catalogs.firstOrNull { it.id == source.catalogId }
            ?: return null
        return CatalogSlot(addon, catalog)
    }

    /** A source's tab / row name: its title, else the add-on catalog's name. */
    fun collectionSourceTitle(source: com.nuvio.shared.collections.NuvioCollectionSource): String {
        source.title?.takeIf { it.isNotBlank() }?.let { return it }
        return when (source.kind) {
            "addon" -> collectionSlot(source)?.let { slot ->
                listOfNotNull(slot.catalog.name, source.genre).joinToString(" · ")
            } ?: source.catalogId.orEmpty()
            "trakt" -> "Trakt list"
            else -> source.tmdbSourceType.orEmpty().lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * One page of a collection source: add-on catalog (with its genre), TMDB list / collection /
     * company / network / discover / person / director, or a public Trakt list. Pass 0 as
     * [cursor] for the first page, then the page's nextCursor.
     */
    @Throws(Exception::class)
    suspend fun collectionSourcePage(source: com.nuvio.shared.collections.NuvioCollectionSource, cursor: Int): com.nuvio.shared.collections.CollectionPage {
        val language = settings.tmdbLanguage
        return when (source.kind) {
            "addon" -> {
                val slot = collectionSlot(source)
                    ?: throw IllegalStateException("Add-on catalog not installed: ${source.addonId} / ${source.catalogId}")
                val row = loadCatalogFiltered(slot, cursor, source.genre.orEmpty())
                com.nuvio.shared.collections.CollectionPage(row.items, row.hasMore, row.nextSkip)
            }
            "trakt" -> {
                val (items, more) = trakt.publicListPage(source.traktListId ?: 0L, source.isShow, cursor + 1,
                    source.sortBy?.takeIf { it.isNotBlank() } ?: "rank", source.sortHow?.takeIf { it.isNotBlank() } ?: "asc")
                com.nuvio.shared.collections.CollectionPage(items, more, cursor + 1)
            }
            else -> {
                val page = cursor + 1
                val id = source.tmdbId ?: 0
                val result = when (source.tmdbSourceType?.uppercase()) {
                    "LIST" -> tmdb.listPage(id, page, language)
                    "COLLECTION" -> tmdb.collectionPage(id, language)
                    "PERSON" -> tmdb.personCreditsPage(id, director = false, isShow = source.isShow, language = language)
                    "DIRECTOR" -> tmdb.personCreditsPage(id, director = true, isShow = source.isShow, language = language)
                    else -> {
                        val isShow = source.isShow || source.tmdbSourceType.equals("NETWORK", ignoreCase = true)
                        tmdb.discoverPage(isShow, com.nuvio.shared.collections.discoverParams(source, isShow,
                            com.nuvio.shared.collections.todayIso(currentTimeMillis())), page, language)
                    }
                }
                val local = source.tmdbSourceType?.uppercase() in setOf("LIST", "COLLECTION", "PERSON", "DIRECTOR")
                val items = if (local) result.items.sortedForCollection(source.sortBy) else result.items
                com.nuvio.shared.collections.CollectionPage(items, result.hasMore, page)
            }
        }
    }

    internal fun <T> observe(flow: Flow<T>, onChange: (T) -> Unit): CoreSubscription =
        JobCancellable(scope.launch { flow.collect { onChange(it) } })
}

/** [primary] (add-on) with its gaps filled from [fallback] (media server). */
internal fun mergeMeta(primary: Meta, fallback: Meta): Meta = primary.copy(
    poster = primary.poster ?: fallback.poster,
    background = primary.background ?: fallback.background,
    logo = primary.logo ?: fallback.logo,
    description = primary.description?.takeIf { it.isNotBlank() } ?: fallback.description,
    releaseInfo = primary.releaseInfo ?: fallback.releaseInfo,
    imdbRating = primary.imdbRating ?: fallback.imdbRating,
    ratingSource = if (primary.imdbRating != null) primary.ratingSource else fallback.ratingSource,
    genres = primary.genres.ifEmpty { fallback.genres },
    runtime = primary.runtime ?: fallback.runtime,
    director = primary.director.ifEmpty { fallback.director },
    writer = primary.writer.ifEmpty { fallback.writer },
    cast = primary.cast.ifEmpty { fallback.cast },
    castMembers = primary.castMembers.ifEmpty { fallback.castMembers },
    videos = primary.videos.ifEmpty { fallback.videos },
    productionCompanies = primary.productionCompanies.ifEmpty { fallback.productionCompanies },
    ageRating = primary.ageRating ?: fallback.ageRating,
    imdbId = primary.imdbId ?: fallback.imdbId,
    released = primary.released ?: fallback.released
)
