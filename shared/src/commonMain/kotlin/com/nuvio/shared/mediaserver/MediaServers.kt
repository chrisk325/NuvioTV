package com.nuvio.shared.mediaserver

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.int
import com.nuvio.shared.data.long
import com.nuvio.shared.data.nonBlank
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.objects
import com.nuvio.shared.data.str
import com.nuvio.shared.model.AddonStreams
import com.nuvio.shared.model.ContentType
import com.nuvio.shared.model.Meta
import com.nuvio.shared.model.MetaCastMember
import com.nuvio.shared.model.MetaPreview
import com.nuvio.shared.model.Video
import com.nuvio.shared.model.PosterShape
import com.nuvio.shared.model.Stream
import com.nuvio.shared.model.StreamBehaviorHints
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import com.nuvio.shared.util.encodeUrlComponent
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType as HttpContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/** A signed-in Jellyfin or Emby server (both speak the MediaBrowser API). */
@Serializable
data class MediaServer(
    val id: String,
    val kind: String,        // "jellyfin" | "emby"
    val name: String,
    val address: String,
    val userId: String,
    val token: String
)

data class ServerLibrary(
    val server: MediaServer,
    val id: String,
    val name: String,
    /** Jellyfin / Emby CollectionType: "movies", "tvshows" or null (mixed). */
    val collectionType: String? = null
) {
    val key: String get() = "${server.id}:$id"

    /** "Name - Movie" / "Name - Series" / "Name - Movies & Series", like add-on catalog rows. */
    val typeLabel: String
        get() = when (collectionType) {
            "movies" -> "Movie"
            "tvshows" -> "Series"
            else -> "Movies & Series"
        }
}

class MediaServerException(message: String) : Exception(message)

/** Port of data/mediaserver (MediaBrowserProvider) for Jellyfin / Emby. */
class MediaServerService(private val http: HttpClient, private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    val servers: List<MediaServer>
        get() = store.getString(KEY)?.let {
            runCatching { json.decodeFromString(ListSerializer(MediaServer.serializer()), it) }.getOrNull()
        }.orEmpty()

    private fun save(list: List<MediaServer>) =
        store.putString(KEY, json.encodeToString(ListSerializer(MediaServer.serializer()), list))

    private val deviceId: String
        get() = store.getString("mediaserver.deviceId") ?: buildString {
            repeat(24) { append("0123456789abcdef"[Random.nextInt(16)]) }
        }.also { store.putString("mediaserver.deviceId", it) }

    private fun authHeader(token: String?) = buildString {
        append("MediaBrowser Client=\"Nuvio\", Device=\"Apple TV\", DeviceId=\"$deviceId\", Version=\"0.1.0\"")
        if (token != null) append(", Token=\"$token\"")
    }

    private fun normalize(address: String): String {
        var a = address.trim().trimEnd('/').removeSuffix("/web/index.html").removeSuffix("/web")
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        return a
    }

    @Throws(Exception::class)
    suspend fun signIn(kind: String, address: String, username: String, password: String): MediaServer {
        val root = normalize(address)
        val name = runCatching {
            AddonJson.parseToJsonElement(http.get("$root/System/Info/Public").bodyAsText()).asObject()?.nonBlank("ServerName")
        }.getOrNull()
        val response = http.post("$root/Users/AuthenticateByName") {
            header("X-Emby-Authorization", authHeader(null))
            header("Authorization", authHeader(null))
            setBody(TextContent("""{"Username":${quote(username)},"Pw":${quote(password)}}""", HttpContentType.Application.Json))
        }
        if (!response.status.isSuccess()) throw MediaServerException("Sign-in failed (HTTP ${response.status.value})")
        val body = AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: throw MediaServerException("Unexpected answer")
        val server = MediaServer(
            id = "srv${currentTimeMillis()}",
            kind = kind,
            name = name ?: root.substringAfter("://"),
            address = root,
            userId = body.obj("User")?.nonBlank("Id") ?: throw MediaServerException("No user id"),
            token = body.nonBlank("AccessToken") ?: throw MediaServerException("No access token")
        )
        save(servers + server)
        return server
    }

    fun remove(serverId: String) = save(servers.filterNot { it.id == serverId })

    @Throws(Exception::class)
    suspend fun libraries(server: MediaServer): List<ServerLibrary> =
        get(server, "/Users/${server.userId}/Views")?.objects("Items").orEmpty()
            .filter { it.str("CollectionType") in setOf("movies", "tvshows", null) }
            .mapNotNull { o ->
                ServerLibrary(server, o.nonBlank("Id") ?: return@mapNotNull null, o.str("Name").orEmpty(), o.str("CollectionType"))
            }

    /** Newest titles of a library as previews; titles with an IMDb id open normal add-on details. */
    @Throws(Exception::class)
    suspend fun latest(library: ServerLibrary, start: Int, limit: Int): List<MetaPreview> =
        get(
            library.server,
            "/Users/${library.server.userId}/Items?ParentId=${library.id}&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&SortBy=DateCreated&SortOrder=Descending&Fields=ProviderIds,Overview,ProductionYear&StartIndex=$start&Limit=$limit"
        )?.objects("Items").orEmpty().mapNotNull { it.toPreview(library.server) }

    /**
     * Movies and shows marked as favourite on the server (Filters=IsFavorite), A–Z. Like the
     * library rows, items need an IMDb id so details and add-on streams resolve.
     */
    @Throws(Exception::class)
    suspend fun favorites(server: MediaServer, limit: Int = 300): List<MetaPreview> =
        get(
            server,
            "/Users/${server.userId}/Items?Filters=IsFavorite&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&SortBy=SortName&SortOrder=Ascending&Fields=ProviderIds,Overview,ProductionYear&Limit=$limit"
        )?.objects("Items").orEmpty().mapNotNull { it.toPreview(server) }

    /** Movies and shows on the server matching [query] (SearchTerm), for the Search tab. */
    @Throws(Exception::class)
    suspend fun search(server: MediaServer, query: String, limit: Int = 40): List<MetaPreview> =
        get(
            server,
            "/Users/${server.userId}/Items?SearchTerm=${encodeUrlComponent(query)}&Recursive=true" +
                "&IncludeItemTypes=Movie,Series&Fields=ProviderIds,Overview,ProductionYear&Limit=$limit"
        )?.objects("Items").orEmpty().mapNotNull { it.toPreview(server) }

    // --- Finding titles ---------------------------------------------------------------------
    // Server titles are matched to add-on titles by IMDb (or TMDB) id. Emby filters by
    // AnyProviderIdEquals; newer Jellyfin ignores that filter, so each server also gets a cached
    // index of its movies and shows by provider id (rebuilt every 15 minutes).

    private val indexLock = Mutex()
    private val providerIndex = mutableMapOf<String, Pair<Long, Map<String, String>>>()

    private fun indexKey(isShow: Boolean, provider: String, id: String) =
        "${if (isShow) "series" else "movie"}|${provider.lowercase()}|${id.lowercase()}"

    private suspend fun index(server: MediaServer): Map<String, String> = indexLock.withLock {
        providerIndex[server.id]?.let { (at, map) -> if (currentTimeMillis() - at < 15 * 60_000L) return@withLock map }
        val map = mutableMapOf<String, String>()
        get(
            server,
            "/Users/${server.userId}/Items?Recursive=true&IncludeItemTypes=Movie,Series&Fields=ProviderIds" +
                "&EnableImageTypes=None&EnableUserData=false"
        )?.objects("Items")?.forEach { item ->
            val id = item.nonBlank("Id") ?: return@forEach
            val isShow = item.str("Type") == "Series"
            item.obj("ProviderIds")?.forEach { (provider, value) ->
                val text = (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@forEach
                map.putIfAbsentCompat(indexKey(isShow, provider, text), id)
            }
        }
        providerIndex[server.id] = currentTimeMillis() to map
        map
    }

    private fun MutableMap<String, String>.putIfAbsentCompat(key: String, value: String) {
        if (key !in this) this[key] = value
    }

    /** The server's item id for a title, by IMDb id ("tt…") or TMDB id ("tmdb:123" / "123"). */
    private suspend fun findItemId(server: MediaServer, imdbId: String?, tmdbId: String?, isShow: Boolean): String? {
        val itemType = if (isShow) "Series" else "Movie"
        if (imdbId != null) {
            runCatching {
                get(
                    server,
                    "/Users/${server.userId}/Items?Recursive=true&IncludeItemTypes=$itemType" +
                        "&AnyProviderIdEquals=${encodeUrlComponent("imdb.$imdbId")}&Fields=ProviderIds&Limit=5"
                )?.objects("Items")?.firstOrNull { o ->
                    o.obj("ProviderIds")?.let { (it.str("Imdb") ?: it.str("IMDB")).equals(imdbId, ignoreCase = true) } == true
                }?.str("Id")
            }.getOrNull()?.let { return it }
        }
        val index = runCatching { index(server) }.getOrDefault(emptyMap())
        imdbId?.let { index[indexKey(isShow, "imdb", it)] }?.let { return it }
        tmdbId?.let { index[indexKey(isShow, "tmdb", it)] }?.let { return it }
        return null
    }

    /** Server-native ids for titles without an IMDb id: "mediaserver:<serverId>:<itemId>". */
    private fun nativeId(server: MediaServer, itemId: String) = "$NATIVE:${server.id}:$itemId"

    private fun parseNative(id: String): Pair<MediaServer, String>? {
        val parts = id.split(':')
        if (parts.size < 3 || parts[0] != NATIVE) return null
        val server = servers.firstOrNull { it.id == parts[1] } ?: return null
        return server to parts[2]
    }

    fun isNativeId(id: String): Boolean = id.startsWith("$NATIVE:")

    // --- Details ----------------------------------------------------------------------------

    /**
     * Details built from the server's own metadata, for titles no add-on describes (or describes
     * only barely): title, synopsis, artwork, rating, genres, cast and, for shows, every episode.
     * [id] is an IMDb id, "tmdb:<id>", or a server-native "mediaserver:…" id. Null when no
     * signed-in server has the title.
     */
    @Throws(Exception::class)
    suspend fun meta(type: String, id: String): Meta? {
        val isShow = type != "movie"
        parseNative(id)?.let { (server, itemId) -> return itemMeta(server, itemId, isShow) }
        val imdb = id.substringBefore(':').takeIf { it.startsWith("tt") }
        val tmdb = id.takeIf { it.startsWith("tmdb:") }?.split(':')?.getOrNull(1)
        if (imdb == null && tmdb == null) return null
        for (server in servers) {
            val itemId = findItemId(server, imdb, tmdb, isShow) ?: continue
            itemMeta(server, itemId, isShow, knownImdb = imdb)?.let { return it }
        }
        return null
    }

    private suspend fun itemMeta(server: MediaServer, itemId: String, isShowHint: Boolean, knownImdb: String? = null): Meta? {
        val item = get(server, "/Users/${server.userId}/Items/$itemId") ?: return null
        val isShow = item.str("Type")?.let { it == "Series" } ?: isShowHint
        val imdb = item.obj("ProviderIds")?.let { it.nonBlank("Imdb") ?: it.nonBlank("IMDB") } ?: knownImdb
        val metaId = imdb ?: nativeId(server, itemId)
        val image = { kind: String, size: String -> "${server.address}/Items/$itemId/Images/$kind?$size&api_key=${server.token}" }
        val people = item.objects("People")
        val runtimeMinutes = item.long("RunTimeTicks")?.let { (it / 600_000_000L).toInt() }?.takeIf { it > 0 }
        return Meta(
            id = metaId,
            type = if (isShow) ContentType.SERIES else ContentType.MOVIE,
            name = item.str("Name") ?: metaId,
            poster = image("Primary", "maxHeight=900"),
            posterShape = PosterShape.POSTER,
            background = if ((item["BackdropImageTags"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true)
                image("Backdrop", "maxWidth=1920") else null,
            logo = if (item.obj("ImageTags")?.str("Logo") != null) image("Logo", "maxWidth=800") else null,
            description = item.str("Overview"),
            releaseInfo = item.int("ProductionYear")?.toString(),
            imdbRating = item.str("CommunityRating")?.toDoubleOrNull()?.let { ((it * 10).toInt() / 10.0).toString() },
            genres = (item["Genres"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
            runtime = runtimeMinutes?.let { "$it min" },
            director = people.filter { it.str("Type") == "Director" }.mapNotNull { it.str("Name") },
            writer = people.filter { it.str("Type") == "Writer" }.mapNotNull { it.str("Name") },
            cast = people.filter { it.str("Type") == "Actor" }.mapNotNull { it.str("Name") },
            castMembers = people.filter { it.str("Type") == "Actor" }.take(30).mapNotNull { p ->
                val name = p.str("Name") ?: return@mapNotNull null
                val photo = p.str("Id")?.takeIf { p.str("PrimaryImageTag") != null }
                    ?.let { "${server.address}/Items/$it/Images/Primary?maxHeight=400&api_key=${server.token}" }
                MetaCastMember(name = name, character = p.str("Role"), photo = photo)
            },
            videos = if (isShow) episodes(server, itemId, imdb) else emptyList(),
            productionCompanies = item.objects("Studios").mapNotNull { it.str("Name") },
            ageRating = item.str("OfficialRating"),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = imdb,
            released = item.str("PremiereDate")
        )
    }

    /** Episodes as "tt…:season:episode" (or server-native ids when the show has no IMDb id). */
    private suspend fun episodes(server: MediaServer, seriesId: String, imdbId: String?): List<Video> =
        get(server, "/Shows/$seriesId/Episodes?UserId=${server.userId}&Fields=Overview,PremiereDate")
            ?.objects("Items").orEmpty().mapNotNull { ep ->
                val season = ep.int("ParentIndexNumber") ?: return@mapNotNull null
                val number = ep.int("IndexNumber") ?: return@mapNotNull null
                val epId = ep.str("Id") ?: return@mapNotNull null
                Video(
                    id = imdbId?.let { "$it:$season:$number" } ?: nativeId(server, epId),
                    title = ep.str("Name") ?: "Episode $number",
                    released = ep.str("PremiereDate"),
                    thumbnail = "${server.address}/Items/$epId/Images/Primary?maxWidth=640&api_key=${server.token}",
                    season = season,
                    episode = number,
                    overview = ep.str("Overview"),
                    runtimeMinutes = ep.long("RunTimeTicks")?.let { (it / 600_000_000L).toInt() } ?: 0
                )
            }

    // --- Favourites -------------------------------------------------------------------------

    /**
     * Marks (or unmarks) the title as a favourite on every signed-in server that has it
     * (POST / DELETE /Users/{id}/FavoriteItems/{itemId}). [id] is an IMDb id or a server-native
     * id. Returns how many servers were updated.
     */
    @Throws(Exception::class)
    suspend fun setFavorite(id: String, isShow: Boolean, favorite: Boolean): Int {
        val targets: List<Pair<MediaServer, String>> = parseNative(id)?.let { listOf(it) }
            ?: servers.mapNotNull { server ->
                runCatching { findItemId(server, id.substringBefore(':').takeIf { it.startsWith("tt") }, null, isShow) }
                    .getOrNull()?.let { server to it }
            }
        var updated = 0
        for ((server, itemId) in targets) {
            try {
                val url = "${server.address}/Users/${server.userId}/FavoriteItems/$itemId"
                val response = if (favorite) http.post(url) { auth(server) } else http.delete(url) { auth(server) }
                if (response.status.isSuccess()) updated++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One unreachable server shouldn't stop the others.
            }
        }
        return updated
    }

    /** Server rows / favourites / search: IMDb id when the server has one, else a native id. */
    private fun JsonObject.toPreview(server: MediaServer): MetaPreview? {
        val itemId = nonBlank("Id") ?: return null
        val imdb = obj("ProviderIds")?.let { it.nonBlank("Imdb") ?: it.nonBlank("IMDB") }
        val isShow = str("Type") == "Series"
        val id = imdb ?: nativeId(server, itemId)
        return MetaPreview(
            id = id,
            type = if (isShow) ContentType.SERIES else ContentType.MOVIE,
            name = str("Name") ?: id,
            poster = "${server.address}/Items/$itemId/Images/Primary?maxHeight=600&api_key=${server.token}",
            posterShape = PosterShape.POSTER,
            background = "${server.address}/Items/$itemId/Images/Backdrop?maxWidth=1920&api_key=${server.token}",
            logo = null,
            description = str("Overview"),
            releaseInfo = int("ProductionYear")?.toString(),
            imdbRating = str("CommunityRating")?.toDoubleOrNull()?.let { ((it * 10).toInt() / 10.0).toString() },
            genres = emptyList(),
            imdbId = imdb
        )
    }

    // --- Streams ----------------------------------------------------------------------------

    /**
     * Copies of a title on the user's servers, as streams (direct play; mpv plays every container).
     * [videoId] is "tt…" / "tt…:season:episode", "tmdb:…" or a server-native "mediaserver:…" id
     * (a movie or an episode item).
     */
    suspend fun streams(type: String, videoId: String): List<AddonStreams> {
        parseNative(videoId)?.let { (server, itemId) ->
            return listOfNotNull(runCatching { itemStreams(server, itemId) }.getOrNull())
        }
        val parts = videoId.split(':')
        val imdb = parts.firstOrNull()?.takeIf { it.startsWith("tt") }
        val tmdb = if (parts.firstOrNull() == "tmdb") parts.getOrNull(1) else null
        if (imdb == null && tmdb == null) return emptyList()
        val tail = if (imdb != null) parts.drop(1) else parts.drop(2)
        val season = tail.getOrNull(0)?.toIntOrNull()
        val episode = tail.getOrNull(1)?.toIntOrNull()
        return servers.mapNotNull { server ->
            try {
                val itemId = findItemId(server, imdb, tmdb, isShow = season != null) ?: return@mapNotNull null
                val targetId = if (season != null && episode != null) {
                    get(server, "/Shows/$itemId/Episodes?UserId=${server.userId}&Season=$season")
                        ?.objects("Items")?.firstOrNull { it.int("IndexNumber") == episode }?.nonBlank("Id")
                        ?: return@mapNotNull null
                } else itemId
                itemStreams(server, targetId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Every media source of one movie / episode item as a direct-play stream. */
    private suspend fun itemStreams(server: MediaServer, itemId: String): AddonStreams? {
        val target = get(server, "/Users/${server.userId}/Items/$itemId") ?: return null
        val sources = target.objects("MediaSources").ifEmpty { listOf(JsonObject(mapOf())) }
        val streams = sources.map { source ->
            val sourceId = source.nonBlank("Id") ?: itemId
            val size = source.long("Size")
            Stream(
                name = "${server.name}\n${source.str("Container")?.uppercase().orEmpty()}",
                title = source.str("Name") ?: target.str("Name"),
                description = listOfNotNull(source.str("Name"), size?.let { "${it / 1_000_000} MB" }).joinToString(" · ").ifEmpty { null },
                url = "${server.address}/Videos/$itemId/stream?static=true&MediaSourceId=$sourceId&api_key=${server.token}",
                ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
                behaviorHints = StreamBehaviorHints(null, null, null, videoSize = size, filename = source.str("Path")?.substringAfterLast('/')),
                addonName = server.name,
                addonLogo = null
            )
        }
        return AddonStreams(server.name, null, streams)
    }

    private suspend fun get(server: MediaServer, path: String): JsonObject? {
        val response = http.get(server.address + path) { auth(server) }
        if (!response.status.isSuccess()) return null
        return AddonJson.parseToJsonElement(response.bodyAsText()).asObject()
    }

    private fun HttpRequestBuilder.auth(server: MediaServer) {
        header("X-Emby-Authorization", authHeader(server.token))
        header("X-Emby-Token", server.token)
    }

    private fun quote(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private companion object {
        const val KEY = "mediaservers.v1"
        const val NATIVE = "mediaserver"
    }
}
