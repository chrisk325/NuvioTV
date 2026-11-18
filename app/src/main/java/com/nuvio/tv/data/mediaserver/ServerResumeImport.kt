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

/**
 * Brings each server's continue-watching list into the app's own Continue watching, for servers with
 * "Import watch progress" on, and takes out what the server finished or dropped. Opening a title already imports
 * its progress; this covers everything in progress.
 */
@Singleton
class ServerResumeImport internal constructor(
    private val repository: ServerRepository,
    private val catalog: ServerCatalog,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val profileManager: ProfileManager,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(
        repository: ServerRepository,
        catalog: ServerCatalog,
        watchProgressRepository: WatchProgressRepository,
        watchProgressPreferences: WatchProgressPreferences,
        profileManager: ProfileManager
    ) : this(
        repository = repository,
        catalog = catalog,
        watchProgressRepository = watchProgressRepository,
        watchProgressPreferences = watchProgressPreferences,
        profileManager = profileManager,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowMs = System::currentTimeMillis
    )

    private val lock = Any()
    private var job: Job? = null
    private val importedAtMs = mutableMapOf<String, Long>()
    private val firstSeenAtMs = mutableMapOf<String, Long>()

    fun connections(): List<ServerConnection> =
        repository.enabledConnections().filter { connection ->
            connection.importWatchState &&
                connection.selectedLibraries.isNotEmpty() &&
                repository.provider(connection)?.supports(ServerCapability.USER_STATE_READ) == true
        }

    /** Imports in the background, at most once every few minutes per server unless [force]. */
    fun refresh(force: Boolean = false): Job? {
        val due = connections().filter { connection ->
            force || synchronized(lock) { importedAtMs[connection.id] }?.let { nowMs() - it >= MIN_INTERVAL_MS } != false
        }
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
        synchronized(lock) { importedAtMs[connection.id] = nowMs() }
        val profileId = profileManager.activeProfileId.value
        val entries = entries(connection.id, catalog.presenter(connection), resume)
        val changed = entries.filter { isNewer(it, profileId) }
        if (changed.isNotEmpty()) watchProgressRepository.saveProgressBatch(changed, syncRemote = false)
        // A full list may have cut off older items, so it can't tell what the server dropped.
        val removed = if (resume.size >= LIMIT) {
            0
        } else {
            removeStale(connection.id, entries.mapTo(mutableSetOf()) { it.videoId }, fetchedAtMs, profileId)
        }
        Log.i(
            TAG,
            "Imported ${changed.size} of ${resume.size} continue-watching items from ${connection.providerId}, " +
                "dropped $removed the server no longer lists"
        )
    }

    /** Takes out of this device's progress what [connectionId] put there and its list no longer has. */
    private suspend fun removeStale(connectionId: String, listed: Set<String>, fetchedAtMs: Long, profileId: Int): Int {
        val stale = watchProgressPreferences.observeAllRawProgress(profileId).first()
            .filter { isStale(it, connectionId, listed, fetchedAtMs) }
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
        resume: List<ServerResume>
    ): List<WatchProgress> =
        resume.mapNotNull { (title, state) ->
            if (state.played || state.positionMs <= 0L || state.durationMs <= 0L) return@mapNotNull null
            entry(present(title), state, connectionId)
        }

    private fun entry(preview: MetaPreview, state: ServerUserState, connectionId: String): WatchProgress? {
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
            lastWatched = state.lastPlayedEpochMs ?: firstSeen(videoId),
            addonBaseUrl = ServerCatalog.baseUrl(connectionId)
        )
    }

    // Without a last-played date an item keeps the time it was first seen, so it neither sinks nor jumps each import.
    private fun firstSeen(videoId: String): Long = synchronized(lock) { firstSeenAtMs.getOrPut(videoId) { nowMs() } }

    private companion object {
        const val TAG = "ServerResumeImport"
        const val LIMIT = 50
        const val MIN_INTERVAL_MS = 3 * 60_000L
    }
}
