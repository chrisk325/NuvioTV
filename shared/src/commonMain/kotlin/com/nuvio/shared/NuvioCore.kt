package com.nuvio.shared

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.model.Addon
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
    val config: NuvioConfig = NuvioConfig.EMPTY
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
    val liveTv = com.nuvio.shared.livetv.LiveTvRepository(httpClient, store)

    // --- Add-ons ---------------------------------------------------------------------------

    val addons: List<Addon> get() = addonRepository.currentAddons

    fun observeAddons(onChange: (List<Addon>) -> Unit): CoreSubscription =
        observe(addonRepository.addons, onChange)

    /** Refreshes manifests; returns the URLs that failed. */
    @Throws(Exception::class)
    suspend fun refreshAddons(): List<String> = addonRepository.refreshAll()

    @Throws(Exception::class)
    suspend fun installAddon(url: String): Addon = addonRepository.install(url)

    @Throws(Exception::class)
    suspend fun removeAddon(baseUrl: String) = addonRepository.remove(baseUrl)

    @Throws(Exception::class)
    suspend fun setAddonEnabled(baseUrl: String, enabled: Boolean) = addonRepository.setEnabled(baseUrl, enabled)

    @Throws(Exception::class)
    suspend fun moveAddon(baseUrl: String, offset: Int) = addonRepository.move(baseUrl, offset)

    // --- Browsing --------------------------------------------------------------------------

    /** Home rows, without the catalogs hidden in Settings. */
    fun homeSlots(): List<CatalogSlot> {
        val hidden = settings.hiddenCatalogKeys.toSet()
        return allHomeSlots().filterNot { it.key in hidden }
    }

    fun allHomeSlots(): List<CatalogSlot> = catalogRepository.homeSlots(addonRepository.currentAddons)

    @Throws(Exception::class)
    suspend fun loadCatalog(slot: CatalogSlot, skip: Int): CatalogRow = catalogRepository.loadSlot(slot, skip)

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
                if (update.isComplete && debrid.hasAnyProvider) {
                    emit(update.copy(groups = debrid.annotateCache(update.groups)))
                }
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
            if (scrobble == ScrobbleAction.STOP && progressPercent >= 80.0) {
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
