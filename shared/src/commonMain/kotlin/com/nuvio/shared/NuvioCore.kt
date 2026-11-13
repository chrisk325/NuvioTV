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
    val simkl = SimklService(httpClient, store, config.simklClientId)
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
        return try {
            val remoteAddons = cloud.pullAddons()
            if (remoteAddons.isNotEmpty()) addonRepository.replaceFromRemote(remoteAddons.map { it.url to it.enabled })
            else pushAddonsToCloud()

            val remoteLibrary = cloud.pullLibrary()
            val localIds = library.items.value.map { it.id }.toSet()
            remoteLibrary.filter { it.id !in localIds }.forEach { library.toggle(it) }
            val remoteIds = remoteLibrary.map { it.id }.toSet()
            cloud.pushLibrary(library.items.value.filter { it.id !in remoteIds })

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
            watched.addAll(cloud.pullWatched())
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Account sync failed"
        }
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
        try {
            return metaRepository.getMeta(addonRepository.currentAddons, type, id, sourceAddonBaseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val tmdbId = id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()
            val imdb = tmdbId?.let { tmdb.imdbIdFor(it, type != "movie") } ?: throw e
            return metaRepository.getMeta(addonRepository.currentAddons, type, imdb, null)
        }
    }

    // --- TMDB / ratings ---------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun tmdbEnrichment(meta: Meta): TmdbEnrichment? {
        if (!settings.tmdbEnrichment || !tmdb.isConfigured) return null
        return tmdb.enrich(meta.id, meta.imdbId, meta.isSeriesLike, settings.tmdbLanguage)
    }

    @Throws(Exception::class)
    suspend fun tmdbPerson(personId: Int): TmdbPersonDetail? = tmdb.person(personId, settings.tmdbLanguage)

    @Throws(Exception::class)
    suspend fun ratings(meta: Meta): List<ExternalRating> {
        if (!settings.showMdbListRatings) return emptyList()
        val imdb = meta.imdbId ?: meta.id.takeIf { it.startsWith("tt") }?.substringBefore(':') ?: return emptyList()
        return mdbList.ratings(settings.mdbListApiKey, imdb, meta.isSeriesLike)
    }

    /** Streams from every add-on, progressively; torrent streams get debrid cache badges once all arrive. */
    fun loadStreams(
        type: String,
        videoId: String,
        embedded: List<Stream>,
        onUpdate: (StreamsUpdate) -> Unit
    ): CoreSubscription = observe(
        streamRepository.streams(addonRepository.currentAddons, type, videoId, embedded)
            .transform { update ->
                emit(update)
                if (!update.isComplete) return@transform
                var groups = update.groups
                if (mediaServers.servers.isNotEmpty()) {
                    val serverGroups = runCatching { mediaServers.streams(type, videoId) }.getOrDefault(emptyList())
                    if (serverGroups.isNotEmpty()) {
                        groups = serverGroups + groups
                        emit(update.copy(groups = groups))
                    }
                }
                if (debrid.hasAnyProvider) emit(update.copy(groups = debrid.annotateCache(groups)))
            },
        onUpdate
    )

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
        observe(watchProgress.entries) { onChange(watchProgress.continueWatching()) }

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
                updatedAtMs = currentTimeMillis()
            )
        )
    }

    fun removeProgress(contentId: String) = watchProgress.remove(contentId)

    // --- Watched & trackers -----------------------------------------------------------------

    fun watchedKey(meta: Meta, video: Video?): String =
        if (video != null && meta.isSeriesLike) video.id else "movie:${meta.imdbId ?: meta.id}"

    fun isWatched(meta: Meta, video: Video?): Boolean = watched.isWatched(watchedKey(meta, video))

    fun observeWatched(onChange: (Set<String>) -> Unit): CoreSubscription = observe(watched.watched, onChange)

    /** Manual watched toggle (also pushed to Trakt / Simkl / AniList when marking watched). */
    fun setWatched(meta: Meta, video: Video?, isWatched: Boolean) {
        watched.mark(watchedKey(meta, video), isWatched)
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
        scope.launch {
            runCatching { trakt.scrobble(media, scrobble, progressPercent) }
            runCatching { simkl.scrobble(media, scrobble, progressPercent) }
            if (scrobble == ScrobbleAction.STOP && cloud.isSignedIn) {
                runCatching { cloud.pushProgress(watchProgress.entries.value.filter { it.contentId == meta.id }.take(5)) }
            }
            if (scrobble == ScrobbleAction.STOP && progressPercent >= 80.0) {
                if (cloud.isSignedIn) runCatching { cloud.pushWatched(meta.id, meta.apiType, meta.name, video?.season, video?.episode) }
                watched.mark(watchedKey(meta, video), true)
                runCatching { aniList.updateProgress(media, completedTitle = false) }
            }
        }
    }

    /** Pulls Trakt history, playback progress and watchlist into local state. Returns an error text or null. */
    @Throws(Exception::class)
    suspend fun syncTrackers(): String? {
        if (!trakt.isConnected) return null
        return try {
            watched.addAll(trakt.watchedKeys())
            trakt.playbackProgress().forEach { remote ->
                val local = watchProgress.progressFor(remote.videoId)
                if (local == null || local.updatedAtMs < remote.updatedAtMs) {
                    watchProgress.update(local?.let { remote.copy(durationMs = it.durationMs, positionMs = (it.durationMs * remote.fraction).toLong()) } ?: remote)
                }
            }
            traktWatchlistCache = trakt.watchlist()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Trakt sync failed"
        }
    }

    /** Last fetched Trakt watchlist (Library tab). */
    var traktWatchlistCache: List<MetaPreview> = emptyList()
        private set

    fun observeLibrary(onChange: (List<SavedItem>) -> Unit): CoreSubscription = observe(library.items, onChange)

    fun isInLibrary(id: String): Boolean = library.contains(id)

    /** Returns true when the title is now saved (mirrored to the Trakt watchlist when connected). */
    fun toggleLibrary(meta: Meta): Boolean = library.toggle(
        SavedItem(
            id = meta.id,
            type = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            background = meta.backdropUrl,
            posterShape = meta.posterShape.name.lowercase(),
            savedAtMs = currentTimeMillis()
        )
    ).also { saved ->
        val imdb = meta.imdbId ?: meta.id.takeIf { it.startsWith("tt") }
        if (trakt.isConnected && imdb != null) {
            scope.launch { runCatching { trakt.setShowInWatchlist(imdb, meta.isSeriesLike, saved) } }
        }
        if (cloud.isSignedIn) scope.launch {
            runCatching {
                if (saved) cloud.pushLibrary(library.items.value.filter { it.id == meta.id })
                else cloud.deleteLibrary(meta.id, meta.apiType)
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
        return skipSegments.segments(TrackedMedia.from(meta, video), durationSeconds)
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

    fun close() = scope.cancel()

    internal fun <T> observe(flow: Flow<T>, onChange: (T) -> Unit): CoreSubscription =
        JobCancellable(scope.launch { flow.collect { onChange(it) } })
}
