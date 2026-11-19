package com.nuvio.tv.data.mediaserver

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mediaserver.mediabrowser.AioMetadataIds
import com.nuvio.tv.domain.model.MetaPreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One episode of a server's Next Up list, as kept on the device. */
@Serializable
data class ServerNextUpEntry(
    val connectionId: String,
    /** The show's id in the app: a Stremio id for AIOMetadata and catalog metadata, else a server ref. */
    val contentId: String,
    val contentType: String = "series",
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val released: String? = null,
    /** When the episode became the one to watch next; orders the row. */
    val availableAt: Long,
    /** The server's own item for the episode ([ServerItemRef.encode]), to mark it played there. */
    val serverItemId: String,
    /** Every id the show answers to (its Stremio id, IMDb, TMDB, ...), to match it with the app's own Next Up. */
    val matchIds: List<String> = emptyList(),
    /** Removed from Next Up here; it stays out until the server names another episode for the show. */
    val dismissed: Boolean = false
)

/** Where Home gets the servers' Next Up lists from. */
interface ServerNextUpSource {
    /** The kept lists of the servers that offer one, or null when no server does. */
    val entries: StateFlow<List<ServerNextUpEntry>?>

    fun refresh(force: Boolean = false)

    /** Takes [entry] out of Next Up until the server moves the show on. */
    fun dismiss(entry: ServerNextUpEntry)

    /** Marks [entry]'s episode played on its server, so the server's Next Up moves on. */
    fun markPlayed(entry: ServerNextUpEntry)

    object None : ServerNextUpSource {
        override val entries: StateFlow<List<ServerNextUpEntry>?> = MutableStateFlow(null)
        override fun refresh(force: Boolean) = Unit
        override fun dismiss(entry: ServerNextUpEntry) = Unit
        override fun markPlayed(entry: ServerNextUpEntry) = Unit
    }
}

@Serializable
internal data class ServerNextUpCache(
    val fetchedAtMs: Long = 0L,
    val entries: List<ServerNextUpEntry> = emptyList(),
    /** The server answered that it has no Next Up list. */
    val unsupported: Boolean = false
)

/**
 * Brings each server's Next Up list into the app's Next Up row, for servers with "Import watch progress" on.
 *
 * This is how AIOMetadata's Jellyfin API is read: its Next Up shelf merges its own play history with the
 * trackers it follows (MDBList, Simkl, AniList, ...), and its item ids carry the Stremio ids of the show and
 * episode, so its entries play through the app's addons like any other. Other Jellyfin and Emby servers work
 * too, with their own items.
 *
 * The lists are kept on the device so Home shows them at once, and the servers are asked again at most once a
 * minute.
 */
@Singleton
class ServerNextUpImport internal constructor(
    private val repository: ServerRepository,
    private val catalog: ServerCatalog,
    private val profileManager: ProfileManager,
    private val store: ServerResumeStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long
) : ServerNextUpSource {
    @Inject
    constructor(
        repository: ServerRepository,
        catalog: ServerCatalog,
        profileManager: ProfileManager,
        store: AndroidServerResumeStore
    ) : this(
        repository = repository,
        catalog = catalog,
        profileManager = profileManager,
        store = store,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowMs = System::currentTimeMillis
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val caches = mutableMapOf<String, ServerNextUpCache>()
    private val mutableEntries = MutableStateFlow<List<ServerNextUpEntry>?>(null)
    private var job: Job? = null
    private var listening = false

    override val entries: StateFlow<List<ServerNextUpEntry>?> = mutableEntries.asStateFlow()

    private fun connections(): List<ServerConnection> =
        repository.enabledConnections().filter { connection ->
            connection.importWatchState &&
                connection.selectedLibraries.any { it.holds(ServerMediaKind.SERIES) } &&
                repository.provider(connection)?.supports(ServerCapability.USER_STATE_READ) == true &&
                !cache(connection.id).unsupported
        }

    override fun refresh(force: Boolean) {
        listen()
        show()
        val due = connections().filter { connection -> force || nowMs() - cache(connection.id).fetchedAtMs >= MIN_INTERVAL_MS }
        if (due.isEmpty()) return
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch {
                due.forEach { connection ->
                    try {
                        import(connection)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Importing Next Up from ${connection.providerId} failed", error)
                    }
                }
            }
        }
    }

    suspend fun import(connection: ServerConnection) {
        val fetchedAtMs = nowMs()
        val listed = try {
            repository.call(connection.id) { provider, session -> provider.nextUp(session, LIMIT) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.UNSUPPORTED) {
                saveCache(connection.id, ServerNextUpCache(fetchedAtMs = fetchedAtMs, unsupported = true))
                show()
            } else {
                Log.w(TAG, "Loading Next Up from ${connection.providerId} failed", error)
            }
            return
        } catch (error: Exception) {
            Log.w(TAG, "Loading Next Up from ${connection.providerId} failed", error)
            return
        }
        val previous = cache(connection.id)
        val entries = nextUpEntries(connection.id, catalog.presenter(connection), listed, previous.entries, fetchedAtMs)
        saveCache(connection.id, ServerNextUpCache(fetchedAtMs = fetchedAtMs, entries = entries))
        show()
        Log.i(TAG, "Next Up from ${connection.providerId}: ${listed.size} listed, ${entries.count { !it.dismissed }} shown")
    }

    override fun dismiss(entry: ServerNextUpEntry) {
        updateEntries(entry.connectionId) { entries ->
            entries.map { if (it.videoId == entry.videoId) it.copy(dismissed = true) else it }
        }
    }

    override fun markPlayed(entry: ServerNextUpEntry) {
        // Out of the row at once; the server's next answer brings the show back with its next episode.
        updateEntries(entry.connectionId) { entries -> entries.filterNot { it.videoId == entry.videoId } }
        val ref = ServerItemRef.parse(entry.serverItemId) ?: return
        scope.launch {
            try {
                repository.call(ref.connectionId) { provider, session ->
                    if (!provider.supports(ServerCapability.USER_STATE_WRITE)) throw ServerException(ServerFailure.UNSUPPORTED)
                    provider.setPlayed(session, ref.itemId, played = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Marking ${ref.itemId} played on ${ref.connectionId} failed", error)
            }
            repository.connection(ref.connectionId)?.let { connection ->
                try {
                    import(connection)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Importing Next Up from ${connection.providerId} failed", error)
                }
            }
        }
    }

    private fun updateEntries(connectionId: String, change: (List<ServerNextUpEntry>) -> List<ServerNextUpEntry>) {
        val cache = cache(connectionId)
        saveCache(connectionId, cache.copy(entries = change(cache.entries)))
        show()
    }

    private fun show() {
        val current = connections()
        mutableEntries.value = if (current.isEmpty()) null else current.flatMap { cache(it.id).entries }
    }

    private fun listen() {
        synchronized(lock) {
            if (listening) return
            listening = true
        }
        // Connections and their lists belong to a profile.
        scope.launch { profileManager.activeProfileId.collect { show() } }
        scope.launch {
            repository.uiState.distinctUntilChangedByRevision().collect { show() }
        }
    }

    private fun kotlinx.coroutines.flow.Flow<ServersUiState>.distinctUntilChangedByRevision() =
        distinctUntilChanged { old, new -> old.revision == new.revision }

    private fun cache(connectionId: String): ServerNextUpCache {
        val key = cacheKey(connectionId)
        return synchronized(lock) {
            caches.getOrPut(key) {
                runCatching { store.read(key) }.getOrNull()
                    ?.let { runCatching { json.decodeFromString(ServerNextUpCache.serializer(), it) }.getOrNull() }
                    ?: ServerNextUpCache()
            }
        }
    }

    private fun saveCache(connectionId: String, cache: ServerNextUpCache) {
        val key = cacheKey(connectionId)
        synchronized(lock) { caches[key] = cache }
        runCatching { store.write(key, json.encodeToString(ServerNextUpCache.serializer(), cache)) }
            .onFailure { Log.w(TAG, "Saving the Next Up list of $connectionId failed", it) }
    }

    private fun cacheKey(connectionId: String) = "nextup1.${profileManager.activeProfileId.value}.$connectionId"

    private companion object {
        const val TAG = "ServerNextUpImport"
        const val LIMIT = 50
        const val MIN_INTERVAL_MS = 60_000L
    }
}

/**
 * The entries to keep from a server's Next Up [listed]: one per show, in the server's order, with the ids the app
 * plays and matches them by. An entry keeps the time it was first seen when the server gives none, and stays
 * dismissed while the server still names the same episode.
 */
internal fun nextUpEntries(
    connectionId: String,
    present: (ServerTitle) -> MetaPreview,
    listed: List<ServerNextUp>,
    previous: List<ServerNextUpEntry>,
    nowMs: Long
): List<ServerNextUpEntry> {
    val before = previous.associateBy { it.videoId }
    return listed.mapNotNull { item ->
        val preview = present(item.show)
        val episode = AioMetadataIds.decode(item.episodeItemId)?.takeIf { it.kind == AioMetadataIds.Kind.EPISODE }
        val series = AioMetadataIds.decode(item.seriesItemId)?.takeIf { it.kind == AioMetadataIds.Kind.SERIES }
        val serverRef = ServerItemRef(connectionId, item.episodeItemId).encode()
        val ids = item.show.externalIds
        val (contentId, videoId) = when {
            // AIOMetadata: the ids are the show's and the episode's Stremio ids.
            episode?.videoId != null -> (series?.base ?: episode.base) to episode.videoId!!
            // A show from the server plays its episode from the server.
            ServerItemRef.isServerId(preview.id) -> preview.id to serverRef
            // A show shown with addon metadata plays "<show id>:<season>:<episode>", like any catalog episode.
            item.season != null && item.episode != null -> preview.id to "${preview.id}:${item.season}:${item.episode}"
            else -> return@mapNotNull null
        }
        val matchIds = listOfNotNull(
            contentId,
            series?.base,
            episode?.base,
            preview.id.takeUnless { ServerItemRef.isServerId(it) },
            ids.imdb,
            ids.tmdb?.let { "tmdb:$it" },
            ids.tvdb?.let { "tvdb:$it" }
        ).distinct()
        val held = before[videoId]
        ServerNextUpEntry(
            connectionId = connectionId,
            contentId = contentId,
            name = preview.name.ifBlank { item.show.preview.name },
            poster = preview.poster ?: item.show.preview.poster,
            backdrop = preview.background ?: item.show.preview.background,
            logo = preview.logo ?: item.show.preview.logo,
            videoId = videoId,
            season = episode?.season ?: item.season,
            episode = episode?.episode ?: item.episode,
            episodeTitle = item.title,
            overview = item.overview,
            thumbnail = item.thumbnail,
            released = item.premiereDate,
            availableAt = item.lastPlayedEpochMs ?: held?.availableAt ?: nowMs,
            serverItemId = serverRef,
            matchIds = matchIds,
            dismissed = held?.dismissed == true
        )
    }.distinctBy { it.contentId }
}
