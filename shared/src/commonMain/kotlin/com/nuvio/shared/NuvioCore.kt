package com.nuvio.shared

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.AddonStreams
import com.nuvio.shared.model.CatalogRow
import com.nuvio.shared.model.Meta
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
import kotlinx.coroutines.CancellationException
import com.nuvio.shared.model.Subtitle
import com.nuvio.shared.repository.WatchProgressRepository
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import com.nuvio.shared.util.isReleaseInFuture
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

data class CalendarEntry(val show: MetaPreview, val video: Video, val releaseEpochMs: Long)

/** Merged details (add-on meta + enabled TMDB groups). People with id 0 have no TMDB page. */
data class DetailContent(
    val meta: Meta,
    val people: List<com.nuvio.shared.tmdb.TmdbPerson>,
    val tmdbRating: String?,
    val tagline: String?,
    val recommendations: List<MetaPreview>,
    val collectionName: String?,
    val collectionParts: List<MetaPreview>
)

/** Handle returned to Swift for observations; call [cancel] when the view goes away. */
interface CoreSubscription {
    fun cancel()
}

private class JobCancellable(private val job: Job) : CoreSubscription {
    override fun cancel() = job.cancel()
}

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
    deviceStore: KeyValueStore = store
) {
    internal val store = store
    internal val http = httpClient
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
    val tmdb = TmdbService(httpClient, config.tmdbApiKey)
    val skipSegments = com.nuvio.shared.tracking.SkipSegmentsService(httpClient, config.introDbApiUrl)
    val liveTv = com.nuvio.shared.livetv.LiveTvRepository(httpClient, store)
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
            // Titles from a Jellyfin / Emby server that no add-on describes: use the server's data.
            val serverId = id.substringBefore(':').takeIf { it.startsWith("tt") } ?: id.takeIf { it.startsWith("tmdb:") }
            if (serverId != null && mediaServers.servers.isNotEmpty()) {
                runCatching { mediaServers.meta(type, serverId) }.getOrNull()?.let { return it }
                if (addonRepository.currentAddons.none { it.enabled }) {
                    throw IllegalStateException(
                        "Couldn't find this title on ${mediaServers.servers.joinToString { it.name }}. " +
                            "Check that the server is reachable and the title is in a Movies or Shows library."
                    )
                }
            }
            val tmdbId = id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()
            val imdb = tmdbId?.let { tmdb.imdbIdFor(it, type != "movie") } ?: throw e
            return metaRepository.getMeta(addonRepository.currentAddons, type, imdb, null)
        }
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
        val e = try {
            tmdbEnrichment(meta)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
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
        if (e != null && e.isShow && s.tmdbUseEpisodes && m.videos.isNotEmpty()) {
            val seasons = m.videos.mapNotNull { it.season }.distinct().filter { it > 0 }.take(30)
            val episodes = seasons.flatMap { season ->
                runCatching { tmdb.seasonEpisodes(e.tmdbId, season, s.tmdbLanguage) }.getOrDefault(emptyList())
            }.associateBy { it.season to it.episode }
            if (episodes.isNotEmpty()) {
                m = m.copy(videos = m.videos.map { v ->
                    val ep = if (v.season != null && v.episode != null) episodes[v.season to v.episode] else null
                    if (ep == null) v else v.copy(
                        title = ep.title ?: v.title,
                        overview = ep.overview ?: v.overview,
                        thumbnail = ep.thumbnail ?: v.thumbnail,
                        runtimeMinutes = ep.runtimeMinutes ?: v.runtimeMinutes
                    )
                })
            }
        }
        // People row: TMDB credits when enabled, otherwise the add-on's own cast (app_extras, with photos).
        val people = tmdbPeople.ifEmpty {
            meta.castMembers.map { com.nuvio.shared.tmdb.TmdbPerson(it.tmdbId ?: 0, it.name, it.character, it.photo) }
        }
        return DetailContent(
            meta = m,
            people = people,
            tmdbRating = if (e != null && s.tmdbUseBasicInfo) e.rating else null,
            tagline = if (e != null && s.tmdbUseBasicInfo) e.tagline else null,
            recommendations = if (e != null && s.tmdbUseMoreLikeThis) e.recommendations else emptyList(),
            collectionName = if (e != null && s.tmdbUseCollections) e.collectionName else null,
            collectionParts = if (e != null && s.tmdbUseCollections) e.collectionParts else emptyList()
        )
    }

    @Throws(Exception::class)
    suspend fun tmdbPerson(personId: Int): TmdbPersonDetail? = tmdb.person(personId, settings.tmdbLanguage)

    private val ratingsCache = LinkedHashMap<String, List<ExternalRating>>()

    private suspend fun cachedRatings(imdb: String, isShow: Boolean): List<ExternalRating> {
        val key = "$imdb|$isShow"
        ratingsCache[key]?.let { return it }
        val result = mdbList.ratings(settings.mdbListApiKey, imdb, isShow)
        ratingsCache[key] = result
        if (ratingsCache.size > 200) ratingsCache.remove(ratingsCache.keys.first())
        return result
    }

    /** Details page rating logos: MDBList (IMDb, else TMDB id), else the IMDb logo with the add-on's rating. */
    @Throws(Exception::class)
    suspend fun ratings(meta: Meta): List<ExternalRating> {
        val mdb = if (settings.showMdbListRatings) mdbRatings(meta.imdbId, meta.id, meta.isSeriesLike) else emptyList()
        return mdb.ifEmpty { imdbLogoRating(meta.imdbRating) }
    }

    /** MDBList ratings by IMDb id, else by the TMDB id of a "tmdb:" id; cached per title. */
    private suspend fun mdbRatings(imdbId: String?, id: String, isShow: Boolean): List<ExternalRating> {
        if (settings.mdbListApiKey.isBlank()) return emptyList()
        val imdb = imdbId?.substringBefore(':')?.takeIf { it.startsWith("tt") }
            ?: id.takeIf { it.startsWith("tt") }?.substringBefore(':')
        if (imdb != null) return cachedRatings(imdb, isShow)
        val tmdb = id.takeIf { it.startsWith("tmdb:") }?.split(':')?.getOrNull(1) ?: return emptyList()
        val key = "tmdb:$tmdb|$isShow"
        ratingsCache[key]?.let { return it }
        val result = mdbList.ratingsByTmdb(settings.mdbListApiKey, tmdb, isShow)
        ratingsCache[key] = result
        if (ratingsCache.size > 200) ratingsCache.remove(ratingsCache.keys.first())
        return result
    }

    private fun imdbLogoRating(raw: String?): List<ExternalRating> {
        val rating = raw?.toDoubleOrNull()?.takeIf { it > 0 } ?: return emptyList()
        val text = kotlin.math.round(rating * 10).toInt().let { "${it / 10}.${it % 10}" }
        return listOf(ExternalRating("imdb", "IMDb", text, "rating_imdb"))
    }

    /**
     * Rating logos for a Home hero item: MDBList by IMDb id, else by TMDB id ("tmdb:…" catalogs);
     * without MDBList ratings (no key, disabled, not found) the add-on's IMDb rating as the IMDb
     * logo. Cached per title.
     */
    @Throws(Exception::class)
    suspend fun heroRatings(preview: MetaPreview): List<ExternalRating> {
        val isShow = com.nuvio.shared.model.isSeriesLikeType(preview.apiType)
        val mdb = if (settings.showMdbListRatings && settings.showMdbListOnHero) {
            mdbRatings(preview.imdbId, preview.id, isShow)
        } else emptyList()
        return mdb.ifEmpty { imdbLogoRating(preview.imdbRating) }
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
        onUpdate: (StreamsUpdate) -> Unit
    ): CoreSubscription = observe(
        streamRepository.streams(addonRepository.currentAddons, type, videoId, embedded)
            .transform { update ->
                emit(update)
                if (!update.isComplete) return@transform
                var groups = update.groups
                if (mediaServers.servers.isNotEmpty()) {
                    val lookupId = videoId.takeIf { it.startsWith("tt") } ?: serverVideoId
                        ?: imdbVideoIdFromTmdb(type, videoId) ?: videoId
                    val serverGroups = runCatching { mediaServers.streams(type, lookupId) }.getOrDefault(emptyList())
                    if (serverGroups.isNotEmpty()) {
                        groups = serverGroups + groups
                        emit(update.copy(groups = groups))
                    }
                }
                if (debrid.hasAnyProvider) emit(update.copy(groups = debrid.annotateCache(groups)))
            },
        onUpdate
    )

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
        observe(kotlinx.coroutines.flow.combine(watchProgress.entries, upNext, watched.watched) { _, _, _ -> Unit }) {
            onChange(continueWatching())
        }

    /**
     * Continue Watching: titles in progress, plus "up next" episodes from trackers (Trakt watched
     * progress, Simkl's Watching list) for shows that have nothing in progress here, newest first.
     * Up next entries have no position (durationMs 0).
     */
    fun continueWatching(): List<WatchProgress> {
        val inProgress = watchProgress.continueWatching()
        val busy = inProgress.map { it.contentId }.toSet()
        val now = currentTimeMillis()
        val hideUnreleased = settings.hideUnreleasedUpNext
        val next = upNext.value.filter {
            it.contentId !in busy && upNextKey(it) !in dismissedUpNext && !watched.isWatched(it.videoId) &&
                !(hideUnreleased && (it.releasedAtMs ?: 0L) > now)
        }
        return (inProgress + next).sortedByDescending { it.updatedAtMs }
    }

    fun isUpNext(progress: WatchProgress): Boolean = progress.durationMs <= 0L

    // --- Up next & dismissals ------------------------------------------------------------------

    private val progressJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val progressListSerializer =
        kotlinx.serialization.builtins.ListSerializer(WatchProgress.serializer())
    private val upNextSources = listOf("simkl", "trakt")
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

    /** Replaces one tracker's up next list. */
    private fun setUpNext(source: String, list: List<WatchProgress>) {
        store.putString("upnext.$source", progressJson.encodeToString(progressListSerializer, list))
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

    fun progressFor(videoId: String): WatchProgress? = watchProgress.progressFor(videoId)

    fun latestProgressFor(contentId: String): WatchProgress? = watchProgress.latestFor(contentId)

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
            watched.mark(watchedKey(meta, meta.videos.firstOrNull { it.id == videoId }), true)
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
    }

    // --- Watched & trackers -----------------------------------------------------------------

    fun watchedKey(meta: Meta, video: Video?): String =
        if (video != null && meta.isSeriesLike) video.id else "movie:${meta.imdbId ?: meta.id}"

    fun isWatched(meta: Meta, video: Video?): Boolean = watched.isWatched(watchedKey(meta, video))

    fun observeWatched(onChange: (Set<String>) -> Unit): CoreSubscription = observe(watched.watched, onChange)

    /** Manual watched toggle (also pushed to Trakt / Simkl / AniList when marking watched). */
    fun setWatched(meta: Meta, video: Video?, isWatched: Boolean) {
        watched.mark(watchedKey(meta, video), isWatched)
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
            if (scrobble == ScrobbleAction.STOP && cloud.isSignedIn) {
                runCatching { cloud.pushProgress(watchProgress.entries.value.filter { it.contentId == meta.id }.take(5)) }
            }
            if (scrobble == ScrobbleAction.STOP && progressPercent >= 80.0) {
                if (cloud.isSignedIn) runCatching { cloud.pushWatched(meta.id, meta.apiType, meta.name, video?.season, video?.episode) }
                watched.mark(watchedKey(meta, video), true)
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
        } else if (storedUpNext("simkl").isNotEmpty()) {
            setUpNext("simkl", emptyList())
        }
        if (!trakt.isConnected && storedUpNext("trakt").isNotEmpty()) setUpNext("trakt", emptyList())
        if (aniList.isConnected) sync("AniList") {
            val state = aniList.completedItems()
            watched.addAll(state.keys)
            setTrackerCompleted("anilist", state.completedSeries)
        }
        // Jellyfin / Emby: what's played there counts as watched here, and its resume points join
        // Continue Watching (newest position wins, as with Trakt / Simkl).
        if (mediaServers.servers.isNotEmpty()) sync("Media server") {
            watched.addAll(mediaServers.playedKeys())
            mediaServers.resumeProgress().forEach(::mergeRemoteProgress)
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
        } else {
            forgetTrackerCompleted(showKeys)
            watched.removeAll(episodeKeys + showKeys)
        }
        val numbers = episodes.map { it.season!! to it.episode!! }
        val show = TrackedMedia.from(meta, null)
        scope.launch {
            runCatching { trakt.markEpisodesWatched(show, numbers, remove = !isWatched) }
            runCatching { simkl.markEpisodesWatched(show, numbers, remove = !isWatched) }
            if (cloud.isSignedIn) runCatching {
                if (isWatched) cloud.pushWatchedEpisodes(meta.id, meta.apiType, meta.name, numbers)
                else cloud.deleteWatched(meta.id, numbers)
            }
        }
    }

    fun setPreviewWatched(preview: MetaPreview, isWatched: Boolean) {
        watched.mark("movie:${preview.imdbId ?: preview.id}", isWatched)
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
        traktWatchlistCache.filter { it.type == com.nuvio.shared.model.ContentType.SERIES }.forEach { shows[it.id] = "series" }
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
    suspend fun loadLiveTv(force: Boolean) = liveTv.load(force)

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
