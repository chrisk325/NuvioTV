package com.nuvio.tv.data.mediaserver

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the app knows of the favourites on the connected media servers, per connection. [writes] goes up each time the
 * app changed a favourite on a server, so screens showing the server's own favourites list know to reload it.
 */
data class ServerFavoritesState(
    val titles: Map<String, List<ServerTitle>> = emptyMap(),
    val loadedKey: String? = null,
    val revision: Int = 0,
    val writes: Int = 0
) {
    internal val index: Map<String, Set<String>> by lazy {
        titles.mapValues { (_, list) -> list.flatMapTo(mutableSetOf()) { it.favoriteKeys() } }
    }
}

enum class ServerFavoriteResult {
    ADDED,
    REMOVED,

    /** None of the connected servers has this title. */
    NOT_FOUND,
    FAILED,

    /** No connected server keeps favourites. */
    UNAVAILABLE
}

/** Enabled connections whose server keeps favourites (Jellyfin, Emby). */
internal fun ServerRepository.favoriteConnections(): List<ServerConnection> =
    enabledConnections().filter { provider(it)?.supports(ServerCapability.FAVORITES) == true }

/**
 * Favourites on Jellyfin and Emby servers. Titles from a server change by their server item; catalog titles (IMDb,
 * TMDB, ...) are matched to the server's copy first, the same way watched state is mirrored.
 */
@Singleton
class ServerFavorites internal constructor(
    private val repository: ServerRepository,
    private val matcher: ServerMatcher,
    private val scope: CoroutineScope,
    private val notifyFailure: suspend () -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        repository: ServerRepository,
        matcher: ServerMatcher
    ) : this(
        repository = repository,
        matcher = matcher,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        notifyFailure = {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.servers_favorite_failed), Toast.LENGTH_SHORT).show()
            }
        }
    )

    private val lock = Any()
    private val _state = MutableStateFlow(ServerFavoritesState())
    val state: StateFlow<ServerFavoritesState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    @Volatile
    private var loadedAtMs = 0L

    fun connections(): List<ServerConnection> = repository.favoriteConnections()

    fun isAvailable(): Boolean = connections().isNotEmpty()

    /** The name of the server type favourites go to ("Jellyfin"), or null when more than one type is connected. */
    fun serverName(): String? =
        connections().mapNotNull { repository.provider(it)?.displayName }.distinct().singleOrNull()

    fun supportsType(type: String?): Boolean = ServerMediaKind.fromContentType(type) != null

    fun isFavorite(id: String, type: String?, state: ServerFavoritesState = _state.value): Boolean {
        val keys = keysFor(id, type)
        if (keys.isEmpty()) return false
        val connected = connections().map { it.id }
        return connected.any { connectionId -> state.index[connectionId]?.any(keys::contains) == true }
    }

    /** Loads the favourites from the servers unless a recent copy for the same servers is held. */
    fun ensureFresh(force: Boolean = false): Job? {
        val key = connectionsKey(connections())
        val current = _state.value
        if (!force && current.loadedKey == key && nowMs() - loadedAtMs < STALE_MS) return null
        synchronized(lock) {
            refreshJob?.takeIf { it.isActive }?.let { return it }
            return scope.launch { refresh() }.also { refreshJob = it }
        }
    }

    suspend fun refresh() {
        val connections = connections()
        val previous = _state.value.titles
        val titles = coroutineScope {
            connections.map { connection ->
                async { connection.id to (load(connection) ?: previous[connection.id].orEmpty()) }
            }.awaitAll()
        }.toMap()
        loadedAtMs = nowMs()
        _state.update { it.copy(titles = titles, loadedKey = connectionsKey(connections), revision = it.revision + 1) }
    }

    /** Mirrors a library change to the servers in the background, saying so only when a server refused it. */
    fun mirror(id: String, type: String, favorite: Boolean, name: String? = null): Job? {
        if (!supportsType(type) || !isAvailable()) return null
        return scope.launch {
            val result = setFavorite(id, type, favorite, name)
            if (result == ServerFavoriteResult.FAILED) notifyFailure()
        }
    }

    suspend fun setFavorite(id: String, type: String, favorite: Boolean, name: String? = null): ServerFavoriteResult {
        val connections = connections()
        if (connections.isEmpty()) return ServerFavoriteResult.UNAVAILABLE
        val kind = ServerMediaKind.fromContentType(type) ?: return ServerFavoriteResult.NOT_FOUND
        val (targets, lookupFailed) = targets(id, type, favorite, connections)
        if (targets.isEmpty()) {
            return when {
                lookupFailed -> ServerFavoriteResult.FAILED
                favorite -> ServerFavoriteResult.NOT_FOUND
                else -> ServerFavoriteResult.REMOVED
            }
        }
        val written = coroutineScope {
            targets.map { ref -> async { ref.takeIf { write(it, favorite) } } }.awaitAll().filterNotNull()
        }
        if (written.isNotEmpty()) {
            val ids = if (ServerItemRef.isServerId(id)) null else parseTrackingExternalIds(id)
            _state.update { state ->
                val titles = state.titles.toMutableMap()
                written.groupBy { it.connectionId }.forEach { (connectionId, refs) ->
                    val encoded = refs.map { it.encode() }.toSet()
                    val kept = titles[connectionId].orEmpty().filterNot { it.preview.id in encoded }
                    titles[connectionId] = if (favorite) {
                        kept + refs.map { ref ->
                            ServerTitle(
                                preview = placeholderPreview(ref.encode(), kind, name.orEmpty()),
                                externalIds = ids ?: TrackingExternalIds()
                            )
                        }
                    } else {
                        kept
                    }
                }
                state.copy(titles = titles, revision = state.revision + 1, writes = state.writes + 1)
            }
            Log.i(TAG, "Set favorite=$favorite on ${written.size} server items for $type $id")
        }
        if (written.size < targets.size || lookupFailed) {
            Log.w(TAG, "Could not set favorite=$favorite on ${targets.size - written.size} of ${targets.size} server items")
            return ServerFavoriteResult.FAILED
        }
        return if (favorite) ServerFavoriteResult.ADDED else ServerFavoriteResult.REMOVED
    }

    private suspend fun targets(
        id: String,
        type: String,
        favorite: Boolean,
        connections: List<ServerConnection>
    ): Pair<List<ServerItemRef>, Boolean> {
        ServerItemRef.parse(id)?.let { ref ->
            return (if (connections.any { it.id == ref.connectionId }) listOf(ref) else emptyList()) to false
        }
        val known = knownRefs(id, type, connections)
        if (!favorite && known.isNotEmpty()) return known to false
        val request = matcher.titleRequest(type, id) ?: return known to false
        var lookupFailed = false
        val matched = connections.filter { matcher.supports(it, request.kind) }.flatMap { connection ->
            try {
                matcher.matchTitle(connection, request, forceRefresh = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lookupFailed = true
                Log.w(TAG, "Looking up $type $id on ${connection.providerId} failed", error)
                emptyList()
            }
        }
        return (known + matched).distinct() to lookupFailed
    }

    private fun knownRefs(id: String, type: String, connections: List<ServerConnection>): List<ServerItemRef> {
        val keys = keysFor(id, type)
        val state = _state.value
        return connections.flatMap { connection ->
            state.titles[connection.id].orEmpty()
                .filter { title -> title.favoriteKeys().any(keys::contains) }
                .mapNotNull { ServerItemRef.parse(it.preview.id) }
        }
    }

    private suspend fun write(ref: ServerItemRef, favorite: Boolean): Boolean =
        try {
            repository.call(ref.connectionId) { provider, session ->
                if (!provider.supports(ServerCapability.FAVORITES)) throw ServerException(ServerFailure.UNSUPPORTED)
                provider.setFavorite(session, ref.itemId, favorite)
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Setting favorite=$favorite on item ${ref.itemId} failed", error)
            false
        }

    private suspend fun load(connection: ServerConnection): List<ServerTitle>? =
        try {
            repository.call(connection.id) { provider, session ->
                provider.favoritesPage(session, start = 0, limit = INDEX_LIMIT).items
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Loading favorites from ${connection.providerId} failed", error)
            null
        }

    private fun connectionsKey(connections: List<ServerConnection>): String =
        connections.joinToString(",") { "${it.id}@${it.remoteUserId}" }

    private fun placeholderPreview(id: String, kind: ServerMediaKind, name: String) = MetaPreview(
        id = id,
        type = kind.domainType(),
        rawType = kind.contentType,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private companion object {
        const val TAG = "ServerFavorites"
        const val INDEX_LIMIT = 500
        const val STALE_MS = 2 * 60_000L
    }
}

/** Keys a title is known by: its server item id, and "<type>|<catalog id>" for each catalog id the server has for it. */
internal fun ServerTitle.favoriteKeys(): Set<String> =
    buildSet {
        add(preview.id)
        val type = ServerMediaKind.fromContentType(preview.apiType)?.contentType ?: preview.apiType
        externalIds.catalogIds().forEach { add("$type|${it.lowercase()}") }
    }

internal fun keysFor(id: String, type: String?): Set<String> {
    if (ServerItemRef.isServerId(id)) return setOf(id)
    val kinds = ServerMediaKind.fromContentType(type)?.let(::listOf) ?: listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES)
    val catalogIds = parseTrackingExternalIds(id).catalogIds()
    return kinds.flatMapTo(mutableSetOf()) { kind -> catalogIds.map { "${kind.contentType}|${it.lowercase()}" } }
}
