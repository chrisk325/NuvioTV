package com.nuvio.tv.data.mediaserver

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The last continue-watching list of one server, by video id. */
@Serializable
internal data class ServerResumeCache(
    val fetchedAtMs: Long = 0L,
    val items: Map<String, Item> = emptyMap()
) {
    @Serializable
    data class Item(val positionMs: Long, val durationMs: Long, val updatedAtMs: Long)
}

/** What one import changes: the entries to add or update, and the videos the server dropped since last time. */
internal class ServerResumeDiff(
    val changed: List<WatchProgress>,
    val dropped: Set<String>,
    val cache: ServerResumeCache
)

/**
 * Brings each server's continue-watching list into the app's own Continue watching, for servers with
 * "Import watch progress" on, and takes out what the server finished or dropped. Opening a title already imports
 * its progress; this covers everything in progress.
 *
 * The last list from each server is kept on the device, so the server is asked at most every few minutes (across
 * restarts too) and only what changed since is added, updated or taken out.
 */
@Singleton
class ServerResumeImport internal constructor(
    private val repository: ServerRepository,
    private val catalog: ServerCatalog,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val profileManager: ProfileManager,
    private val store: ServerResumeStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(
        repository: ServerRepository,
        catalog: ServerCatalog,
        watchProgressRepository: WatchProgressRepository,
        watchProgressPreferences: WatchProgressPreferences,
        profileManager: ProfileManager,
        store: AndroidServerResumeStore
    ) : this(
        repository = repository,
        catalog = catalog,
        watchProgressRepository = watchProgressRepository,
        watchProgressPreferences = watchProgressPreferences,
        profileManager = profileManager,
        store = store,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowMs = System::currentTimeMillis
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    private val caches = mutableMapOf<String, ServerResumeCache>()

    fun connections(): List<ServerConnection> =
        repository.enabledConnections().filter { connection ->
            connection.importWatchState &&
                connection.selectedLibraries.isNotEmpty() &&
                repository.provider(connection)?.supports(ServerCapability.USER_STATE_READ) == true
        }

    /** Imports in the background, at most once every few minutes per server unless [force]. */
    fun refresh(force: Boolean = false): Job? {
        val due = connections().filter { connection -> force || nowMs() - cache(connection.id).fetchedAtMs >= MIN_INTERVAL_MS }
        if (due.isEmpty()) return null
        synchronized(lock) {
            job?.takeIf { it.isActive }?.let { return it }
            return scope.launch {
                due.forEach { connection ->
                    try {
                        import(connection)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Importing continue watching from ${connection.providerId} failed", error)
                    }
                }
            }.also { job = it }
        }
    }

    suspend fun import(connection: ServerConnection) {
        val fetchedAtMs = nowMs()
        val resume = try {
            repository.call(connection.id) { provider, session -> provider.resumeProgress(session, LIMIT) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Loading continue watching from ${connection.providerId} failed", error)
            return
        }
        val profileId = profileManager.activeProfileId.value
        val previous = cache(connection.id)
        val entries = entries(connection.id, catalog.presenter(connection), resume, previous)
        // A full list may have cut off older items, so it can't tell what the server dropped.
        val diff = diff(previous, entries, complete = resume.size < LIMIT, fetchedAtMs = fetchedAtMs)
        saveCache(connection.id, diff.cache)
        val applied = diff.changed.filter { isNewer(it, profileId) }
        if (applied.isNotEmpty()) watchProgressRepository.saveProgressBatch(applied, syncRemote = false)
        val removed = if (diff.dropped.isEmpty()) 0 else removeStale(connection.id, diff.dropped, fetchedAtMs, profileId)
        Log.i(
            TAG,
            "Continue watching from ${connection.providerId}: ${resume.size} listed, ${diff.changed.size} new or " +
                "changed, $removed dropped"
        )
    }

    /**
     * Compares [entries] with the list kept from the last import: only new or changed items are applied, and only
     * items the server listed last time and no longer does are dropped. A [complete] list is needed to drop anything.
     */
    internal fun diff(
        previous: ServerResumeCache,
        entries: List<WatchProgress>,
        complete: Boolean,
        fetchedAtMs: Long
    ): ServerResumeDiff {
        val items = entries.associate { entry ->
            entry.videoId to ServerResumeCache.Item(entry.position, entry.duration, entry.lastWatched)
        }
        val changed = entries.filter { previous.items[it.videoId] != items.getValue(it.videoId) }
        val dropped = if (complete) previous.items.keys - items.keys else emptySet()
        // Items an incomplete list left out are kept, so their first-seen time and their removal still count later.
        val kept = if (complete) items else previous.items + items
        return ServerResumeDiff(changed, dropped, ServerResumeCache(fetchedAtMs, kept))
    }

    /** Takes out of this device's progress the [dropped] videos [connectionId] put there. */
    private suspend fun removeStale(connectionId: String, dropped: Set<String>, fetchedAtMs: Long, profileId: Int): Int {
        val stale = watchProgressPreferences.observeAllRawProgress(profileId).first()
            .filter { it.videoId in dropped && isStale(it, connectionId, emptySet(), fetchedAtMs) }
        stale.groupBy { it.contentId }.forEach { (contentId, items) ->
            val episodes = items.mapNotNull { item -> item.season?.let { season -> item.episode?.let { season to it } } }
            if (episodes.isNotEmpty()) {
                watchProgressPreferences.removeProgressBatch(contentId, episodes, profileId)
            } else {
                watchProgressPreferences.removeProgress(contentId, profileId = profileId)
            }
        }
        return stale.size
    }

    private suspend fun isNewer(progress: WatchProgress, profileId: Int): Boolean {
        val season = progress.season
        val episode = progress.episode
        val existing = if (season != null && episode != null) {
            watchProgressPreferences.getEpisodeProgress(progress.contentId, season, episode, profileId).first()
        } else {
            watchProgressPreferences.getProgress(progress.contentId, profileId).first()
        }
        return existing == null || existing.lastWatched < progress.lastWatched
    }

    /**
     * True for progress this server put in Continue watching that its list no longer has: finished or removed on
     * the server. Progress from playing here is saved without the server as its source and stays, as does anything
     * updated since the list was fetched.
     */
    internal fun isStale(progress: WatchProgress, connectionId: String, listed: Set<String>, fetchedAtMs: Long): Boolean =
        progress.addonBaseUrl == ServerCatalog.baseUrl(connectionId) &&
            progress.videoId !in listed &&
            progress.lastWatched < fetchedAtMs

    internal fun entries(
        connectionId: String,
        present: (ServerTitle) -> MetaPreview,
        resume: List<ServerResume>,
        previous: ServerResumeCache = ServerResumeCache()
    ): List<WatchProgress> {
        val now = nowMs()
        return resume.mapNotNull { (title, state) ->
            if (state.played || state.positionMs <= 0L || state.durationMs <= 0L) return@mapNotNull null
            entry(present(title), state, connectionId) { videoId -> previous.items[videoId]?.updatedAtMs ?: now }
        }.distinctBy { it.videoId }
    }

    private fun entry(
        preview: MetaPreview,
        state: ServerUserState,
        connectionId: String,
        firstSeen: (String) -> Long
    ): WatchProgress? {
        val isSeries = ServerMediaKind.fromContentType(preview.apiType) == ServerMediaKind.SERIES
        val videoId = when {
            !isSeries -> preview.id
            // A show from the server plays its episode from the server.
            ServerItemRef.isServerId(preview.id) -> state.videoId
            // A show shown with addon metadata plays "<show id>:<season>:<episode>", like any catalog episode.
            state.season != null && state.episode != null -> "${preview.id}:${state.season}:${state.episode}"
            else -> return null
        }
        return WatchProgress(
            contentId = preview.id,
            contentType = preview.apiType,
            name = preview.name,
            poster = preview.poster,
            backdrop = preview.background,
            logo = preview.logo,
            videoId = videoId,
            season = state.season.takeIf { isSeries },
            episode = state.episode.takeIf { isSeries },
            episodeTitle = state.title.takeIf { isSeries },
            position = state.positionMs,
            duration = state.durationMs,
            // Without a last-played date an item keeps the time it was first seen, so it neither sinks nor jumps.
            lastWatched = state.lastPlayedEpochMs ?: firstSeen(videoId),
            addonBaseUrl = ServerCatalog.baseUrl(connectionId)
        )
    }

    private fun cache(connectionId: String): ServerResumeCache {
        val key = cacheKey(connectionId)
        return synchronized(lock) {
            caches.getOrPut(key) {
                runCatching { store.read(key) }.getOrNull()
                    ?.let { runCatching { json.decodeFromString(ServerResumeCache.serializer(), it) }.getOrNull() }
                    ?: ServerResumeCache()
            }
        }
    }

    private fun saveCache(connectionId: String, cache: ServerResumeCache) {
        val key = cacheKey(connectionId)
        synchronized(lock) { caches[key] = cache }
        runCatching { store.write(key, json.encodeToString(ServerResumeCache.serializer(), cache)) }
            .onFailure { Log.w(TAG, "Saving the continue-watching list of $connectionId failed", it) }
    }

    // Connections belong to a profile, so the kept lists do too.
    private fun cacheKey(connectionId: String) = "resume.${profileManager.activeProfileId.value}.$connectionId"

    /** Forgets a server's last list, so its next import brings everything in again. */
    fun forget(connectionId: String) {
        val key = cacheKey(connectionId)
        synchronized(lock) { caches.remove(key) }
        runCatching { store.write(key, null) }
    }

    private companion object {
        const val TAG = "ServerResumeImport"
        const val LIMIT = 50
        const val MIN_INTERVAL_MS = 3 * 60_000L
    }
}
