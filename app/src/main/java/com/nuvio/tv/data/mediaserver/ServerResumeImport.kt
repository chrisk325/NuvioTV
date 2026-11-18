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

/** One item of a server's continue-watching list, as kept on the device. */
@Serializable
internal data class ServerResumeEntry(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val addonBaseUrl: String? = null
) {
    fun toProgress() = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        addonBaseUrl = addonBaseUrl
    )
}

internal fun WatchProgress.toResumeEntry() = ServerResumeEntry(
    contentId = contentId,
    contentType = contentType,
    name = name,
    poster = poster,
    backdrop = backdrop,
    logo = logo,
    videoId = videoId,
    season = season,
    episode = episode,
    episodeTitle = episodeTitle,
    position = position,
    duration = duration,
    lastWatched = lastWatched,
    addonBaseUrl = addonBaseUrl
)

/**
 * One server's continue-watching list as last fetched, kept on the device. [dismissed] holds the videos removed
 * from Continue watching here; they stay out until the server changes them.
 */
@Serializable
internal data class ServerResumeCache(
    val fetchedAtMs: Long = 0L,
    val entries: List<ServerResumeEntry> = emptyList(),
    val dismissed: Set<String> = emptySet()
) {
    val shown: List<WatchProgress>
        get() = entries.filterNot { it.videoId in dismissed }.map(ServerResumeEntry::toProgress)
}

/**
 * Shows each server's continue-watching list in the app's own Continue watching, for servers with
 * "Import watch progress" on.
 *
 * The list is kept on the device and shown from there, apart from the app's synced progress, so restarts and
 * syncs leave it alone. The server is asked again at most every few minutes, and Continue watching changes only
 * when the server's list did: new and moved items show up, and items the server finished or dropped go.
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
    private var listening = false
    private val cleanedUpProfiles = mutableSetOf<Int>()

    fun connections(): List<ServerConnection> =
        repository.enabledConnections().filter { connection ->
            connection.importWatchState &&
                connection.selectedLibraries.isNotEmpty() &&
                repository.provider(connection)?.supports(ServerCapability.USER_STATE_READ) == true
        }

    /** Shows the kept lists, then asks the servers whose list is older than a few minutes, unless [force]. */
    fun refresh(force: Boolean = false): Job? {
        show()
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

    /** After a title's details came from [connectionId], asks again soon so its progress shows. */
    fun refreshSoon(connectionId: String): Job? {
        val connection = connections().firstOrNull { it.id == connectionId } ?: return null
        if (nowMs() - cache(connection.id).fetchedAtMs < SOON_INTERVAL_MS) return null
        return scope.launch {
            try {
                import(connection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Importing continue watching from ${connection.providerId} failed", error)
            }
        }
    }

    /** Puts the kept lists of the current servers in Continue watching; nothing changes when they are the same. */
    fun show() {
        listen()
        cleanUpOnce()
        watchProgressRepository.setExternalProgress(connections().flatMap { cache(it.id).shown })
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
        val previous = cache(connection.id)
        val entries = entries(connection.id, catalog.presenter(connection), resume, previous)
        // A full list may have cut off older items, so it can't tell what the server dropped.
        val next = next(previous, entries, complete = resume.size < LIMIT, fetchedAtMs = fetchedAtMs)
        saveCache(connection.id, next)
        show()
        Log.i(TAG, "Continue watching from ${connection.providerId}: ${resume.size} listed, ${next.shown.size} shown")
    }

    /**
     * The list to keep after an import: the server's [entries], plus, when the list wasn't [complete], what it may
     * have cut off. A video stays dismissed while the server reports it unchanged.
     */
    internal fun next(
        previous: ServerResumeCache,
        entries: List<WatchProgress>,
        complete: Boolean,
        fetchedAtMs: Long
    ): ServerResumeCache {
        val current = entries.map { it.toResumeEntry() }
        val listed = current.mapTo(mutableSetOf()) { it.videoId }
        val kept = if (complete) current else current + previous.entries.filterNot { it.videoId in listed }
        val before = previous.entries.associateBy { it.videoId }
        val after = kept.associateBy { it.videoId }
        val dismissed = previous.dismissed.filterTo(mutableSetOf()) { videoId ->
            val old = before[videoId]
            val new = after[videoId]
            old != null && new != null && old.position == new.position && old.lastWatched == new.lastWatched
        }
        return ServerResumeCache(fetchedAtMs, kept, dismissed)
    }

    internal fun entries(
        connectionId: String,
        present: (ServerTitle) -> MetaPreview,
        resume: List<ServerResume>,
        previous: ServerResumeCache = ServerResumeCache()
    ): List<WatchProgress> {
        val seen = previous.entries.associate { it.videoId to it.lastWatched }
        val now = nowMs()
        return resume.mapNotNull { (title, state) ->
            if (state.played || state.positionMs <= 0L || state.durationMs <= 0L) return@mapNotNull null
            entry(present(title), state, connectionId) { videoId -> seen[videoId] ?: now }
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

    /** Remembers what Continue watching removed, so it stays out until the server changes it. */
    internal fun dismiss(removed: List<WatchProgress>) {
        removed.groupBy { ServerCatalog.connectionId(it.addonBaseUrl.orEmpty()) }.forEach { (connectionId, entries) ->
            if (connectionId == null) return@forEach
            val cache = cache(connectionId)
            saveCache(connectionId, cache.copy(dismissed = cache.dismissed + entries.map { it.videoId }))
        }
    }

    private fun listen() {
        synchronized(lock) {
            if (listening) return
            listening = true
        }
        scope.launch { watchProgressRepository.externalProgressRemoved.collect { removed -> dismiss(removed) } }
    }

    // Earlier versions stored these entries with the app's synced progress, where syncs wiped them.
    private fun cleanUpOnce() {
        val profileId = profileManager.activeProfileId.value
        synchronized(lock) { if (!cleanedUpProfiles.add(profileId)) return }
        val sources = connections().mapTo(mutableSetOf()) { ServerCatalog.baseUrl(it.id) }
        if (sources.isEmpty()) return
        scope.launch {
            try {
                // Progress from playing here is saved without the server as its source, so it stays.
                val stored = watchProgressPreferences.observeAllRawProgress(profileId).first()
                    .filter { it.addonBaseUrl in sources }
                stored.groupBy { it.contentId }.forEach { (contentId, items) ->
                    val episodes = items.mapNotNull { item -> item.season?.let { season -> item.episode?.let { season to it } } }
                    if (episodes.isNotEmpty()) {
                        watchProgressPreferences.removeProgressBatch(contentId, episodes, profileId)
                    } else {
                        watchProgressPreferences.removeProgress(contentId, profileId = profileId)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Cleaning up stored server progress failed", error)
            }
        }
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

    // Connections belong to a profile, so the kept lists do too. "resume2": the list earlier versions kept under
    // "resume" held no entries to show.
    private fun cacheKey(connectionId: String) = "resume2.${profileManager.activeProfileId.value}.$connectionId"

    /** Forgets a server's kept list and takes it out of Continue watching; its next import brings everything in. */
    fun forget(connectionId: String) {
        val key = cacheKey(connectionId)
        synchronized(lock) { caches.remove(key) }
        runCatching { store.write(key, null) }
        show()
    }

    private companion object {
        const val TAG = "ServerResumeImport"
        const val LIMIT = 50
        const val MIN_INTERVAL_MS = 3 * 60_000L
        const val SOON_INTERVAL_MS = 30_000L
    }
}
