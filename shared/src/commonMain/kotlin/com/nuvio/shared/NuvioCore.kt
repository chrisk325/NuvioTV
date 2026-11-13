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
import kotlinx.coroutines.launch

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
    dispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val client = StremioClient(httpClient)

    val addonRepository = AddonRepository(client, store)
    val catalogRepository = CatalogRepository(client)
    val searchRepository = SearchRepository(catalogRepository)
    val metaRepository = MetaRepository(client)
    val streamRepository = StreamRepository(client)
    val watchProgress = WatchProgressRepository(store)
    val library = LibraryRepository(store)

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

    fun homeSlots(): List<CatalogSlot> = catalogRepository.homeSlots(addonRepository.currentAddons)

    @Throws(Exception::class)
    suspend fun loadCatalog(slot: CatalogSlot, skip: Int): CatalogRow = catalogRepository.loadSlot(slot, skip)

    @Throws(Exception::class)
    suspend fun search(query: String): List<CatalogRow> =
        searchRepository.search(addonRepository.currentAddons, query)

    @Throws(Exception::class)
    suspend fun meta(type: String, id: String, sourceAddonBaseUrl: String?): Meta =
        metaRepository.getMeta(addonRepository.currentAddons, type, id, sourceAddonBaseUrl)

    fun loadStreams(
        type: String,
        videoId: String,
        embedded: List<Stream>,
        onUpdate: (StreamsUpdate) -> Unit
    ): CoreSubscription = observe(
        streamRepository.streams(addonRepository.currentAddons, type, videoId, embedded),
        onUpdate
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

    fun observeLibrary(onChange: (List<SavedItem>) -> Unit): CoreSubscription = observe(library.items, onChange)

    fun isInLibrary(id: String): Boolean = library.contains(id)

    /** Returns true when the title is now saved. */
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
    )

    /** Preview used to open the detail screen from a continue-watching card. */
    fun previewFor(progress: WatchProgress): MetaPreview = SavedItem(
        id = progress.contentId,
        type = progress.contentType,
        name = progress.name,
        poster = progress.poster,
        background = progress.background,
        savedAtMs = progress.updatedAtMs
    ).toPreview()

    fun close() = scope.cancel()

    private fun <T> observe(flow: Flow<T>, onChange: (T) -> Unit): CoreSubscription =
        JobCancellable(scope.launch { flow.collect { onChange(it) } })
}
