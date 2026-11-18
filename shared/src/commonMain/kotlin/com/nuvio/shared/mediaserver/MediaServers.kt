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
import com.nuvio.shared.model.WatchProgress
import com.nuvio.shared.util.currentTimeMillis
import com.nuvio.shared.util.parseReleaseEpochMs
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
            // (EnableImageTypes=None is not a valid value and made Jellyfin reject the request.)
            "/Users/${server.userId}/Items?Recursive=true&IncludeItemTypes=Movie,Series&Fields=ProviderIds" +
                "&EnableImages=false&EnableUserData=false"
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

    /** Items already shown from a server (rows, favourites, search), by provider id: no lookup needed. */
    private val seenItems = mutableMapOf<String, String>()

    private fun remember(server: MediaServer, isShow: Boolean, providerIds: JsonObject?, itemId: String) {
        providerIds?.forEach { (provider, value) ->
            val text = (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@forEach
            seenItems["${server.id}|${indexKey(isShow, provider, text)}"] = itemId
        }
    }

    /** The server's item id for a title, by IMDb id ("tt…") or TMDB id ("tmdb:123" / "123"). */
    private suspend fun findItemId(server: MediaServer, imdbId: String?, tmdbId: String?, isShow: Boolean): String? {
        imdbId?.let { seenItems["${server.id}|${indexKey(isShow, "imdb", it)}"] }?.let { return it }
        tmdbId?.let { seenItems["${server.id}|${indexKey(isShow, "tmdb", it)}"] }?.let { return it }
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

    /** One item; /Users/{id}/Items/{item} (Emby, Jellyfin ≤10.9) or /Items/{item}?userId= (newer Jellyfin). */
    private suspend fun item(server: MediaServer, itemId: String): JsonObject? =
        get(server, "/Users/${server.userId}/Items/$itemId") ?: get(server, "/Items/$itemId?userId=${server.userId}")

    private suspend fun itemMeta(server: MediaServer, itemId: String, isShowHint: Boolean, knownImdb: String? = null): Meta? {
        val item = item(server, itemId) ?: return null
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
            // Jellyfin / Emby's community rating comes from TMDB (or TVDB), not IMDb.
            ratingSource = "tmdb",
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
    suspend fun setFavorite(id: String, isShow: Boolean, favorite: Boolean): Int =
        setFavoriteAny(listOf(id), isShow, favorite)

    /**
     * Like [setFavorite] for a title known by several ids (IMDb "tt…", "tmdb:…", native): each
     * server uses the first one it can place, so a show the server identifies by TMDB only (or
     * an add-on that only gives a TMDB id) still becomes a favourite there.
     */
    @Throws(Exception::class)
    suspend fun setFavoriteAny(ids: List<String>, isShow: Boolean, favorite: Boolean): Int {
        val candidates = ids.filter { it.isNotBlank() }.distinct()
        val targets: List<Pair<MediaServer, String>> = servers.mapNotNull { server ->
            for (id in candidates) {
                parseNative(id)?.let { (owner, itemId) ->
                    if (owner.id == server.id) return@mapNotNull server to itemId
                }
                val imdb = id.substringBefore(':').takeIf { it.startsWith("tt") }
                val tmdb = if (id.startsWith("tmdb:")) id.split(':').getOrNull(1) else null
                if (imdb == null && tmdb == null) continue
                runCatching { findItemId(server, imdb, tmdb, isShow) }.getOrNull()?.let { return@mapNotNull server to it }
            }
            null
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
        remember(server, isShow, obj("ProviderIds"), itemId)
        val id = imdb ?: nativeId(server, itemId)
        val tmdbId = obj("ProviderIds")?.nonBlank("Tmdb")
        if (imdb == null && tmdbId != null) nativeTmdb[id] = tmdbId
        idAliases[id] = setOfNotNull(imdb, tmdbId?.let { "tmdb:$it" }, nativeId(server, itemId))
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
            imdbId = imdb,
            ratingSource = "tmdb"
        )
    }

    // --- Streams ----------------------------------------------------------------------------

    /**
     * Copies of a title on the user's servers, as streams (direct play; mpv plays every container).
     * [videoId] is "tt…" / "tt…:season:episode", "tmdb:…" or a server-native "mediaserver:…" id
     * (a movie or an episode item).
     */
    suspend fun streams(type: String, videoId: String): List<AddonStreams> = streamsFor(type, listOf(videoId), null)

    /**
     * Streams from each server for the first of [candidateIds] it can place (IMDb, TMDB or native
     * ids, with ":season:episode" for episodes). Episodes are matched by season and number, else
     * by air date ([released]), since daily / talk shows are often numbered differently by TMDB
     * and by the server's metadata (TVDB).
     */
    suspend fun streamsFor(type: String, candidateIds: List<String>, released: String?): List<AddonStreams> =
        servers.mapNotNull { server ->
            for (candidate in candidateIds.distinct()) {
                val streams = try {
                    serverStreams(server, candidate, released)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (streams != null) return@mapNotNull streams
            }
            null
        }

    private suspend fun serverStreams(server: MediaServer, videoId: String, released: String?): AddonStreams? {
        parseNative(videoId)?.let { (owner, itemId) ->
            return if (owner.id == server.id) itemStreams(server, itemId) else null
        }
        val parts = videoId.split(':')
        val imdb = parts.firstOrNull()?.takeIf { it.startsWith("tt") }
        val tmdb = if (parts.firstOrNull() == "tmdb") parts.getOrNull(1) else null
        if (imdb == null && tmdb == null) return null
        val tail = if (imdb != null) parts.drop(1) else parts.drop(2)
        val season = tail.getOrNull(0)?.toIntOrNull()
        val episode = tail.getOrNull(1)?.toIntOrNull()
        val isShow = season != null
        val itemId = findItemId(server, imdb, tmdb, isShow) ?: return null
        val targetId = if (isShow) findEpisode(server, itemId, season, episode, released) ?: return null else itemId
        return itemStreams(server, targetId)
    }

    private suspend fun findEpisode(server: MediaServer, seriesId: String, season: Int?, episode: Int?, released: String?): String? {
        if (season != null && episode != null) {
            get(server, "/Shows/$seriesId/Episodes?UserId=${server.userId}&Season=$season")
                ?.objects("Items")
                ?.firstOrNull { e ->
                    val first = e.int("IndexNumber")
                    first == episode || (first != null && e.int("IndexNumberEnd")?.let { episode in first..it } == true)
                }
                ?.nonBlank("Id")?.let { return it }
        }
        val target = parseReleaseEpochMs(released?.take(10)) ?: return null
        val day = 86_400_000L
        return get(server, "/Shows/$seriesId/Episodes?UserId=${server.userId}&Fields=PremiereDate")
            ?.objects("Items").orEmpty()
            .mapNotNull { e ->
                val aired = parseReleaseEpochMs(e.str("PremiereDate")?.take(10)) ?: return@mapNotNull null
                e to kotlin.math.abs(aired - target)
            }
            .filter { it.second <= day }
            .minByOrNull { it.second }
            ?.first?.nonBlank("Id")
    }

    /**
     * Every media source of one movie / episode item as a stream.
     *
     * The stored item only lists the sources the library knows about. Plugins that bridge
     * Stremio add-ons (AIOStreams via Gelato and similar) store placeholder sources such as
     * "Streams load when played" and only resolve the real list when a player asks for
     * playback info, so this asks POST /Items/{id}/PlaybackInfo (once without and once per
     * stored source id) and lists what comes back, falling back to the stored sources.
     */
    private suspend fun itemStreams(server: MediaServer, itemId: String): AddonStreams? {
        val target = item(server, itemId) ?: return null
        val stored = target.objects("MediaSources")
        val resolved = LinkedHashMap<String, JsonObject>()
        suspend fun ask(sourceId: String?) {
            val info = runCatching { playbackInfo(server, itemId, sourceId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return
            for (source in info.objects("MediaSources")) {
                // Entries without an id or path (AIOStreams statistics / info) are kept too.
                val key = source.nonBlank("Id") ?: source.nonBlank("Path") ?: ("info|" + (source.str("Name") ?: source.toString()))
                resolved.getOrPut(key) { source }
            }
        }
        ask(null)
        val storedIds = stored.mapNotNull { it.nonBlank("Id") }.toSet()
        for (id in storedIds.take(MAX_SOURCE_PROBES)) {
            // The first call already produced new sources: no need to probe each stored one.
            if (resolved.values.any { (it.nonBlank("Id") ?: "") !in storedIds && !it.isPlaceholderSource() }) break
            ask(id)
        }
        // Still nothing new: the plugin may have saved the resolved versions on the item.
        val refreshed = if (resolved.isEmpty() || resolved.values.all { it.isPlaceholderSource() }) {
            item(server, itemId)?.objects("MediaSources").orEmpty()
        } else emptyList()
        // Same id and same name only collapse true duplicates (info entries can share an id).
        val all = (resolved.values + refreshed + stored)
            .distinctBy { (it.nonBlank("Id") ?: it.nonBlank("Path") ?: it.toString()) + "|" + it.str("Name") }
        // Drop only the item's stored stand-ins ("Streams load when played"): placeholders the
        // plugin returns with the resolved list (AIOStreams statistics / info entries) are kept.
        // Everything the server lists is shown, playable or not (statistics / info entries become
        // text cards); only the plugin's "load when played" stand-ins are left out.
        val real = all.filterNot { source -> source.isPlaceholderSource() && isStandIn(source) }
        val sources = real.ifEmpty { all }.ifEmpty { listOf(JsonObject(mapOf())) }
        val streams = sources.map { source -> sourceStream(server, itemId, target, source) }
        return AddonStreams(server.name, null, streams)
    }

    private fun sourceStream(server: MediaServer, itemId: String, target: JsonObject, source: JsonObject): Stream {
        val sourceId = source.nonBlank("Id") ?: itemId
        val size = source.long("Size")
        val path = source.nonBlank("Path")
        val remote = path?.takeIf {
            (it.startsWith("http://") || it.startsWith("https://")) &&
                (source.str("Protocol")?.equals("Http", ignoreCase = true) != false)
        }
        val video = source.objects("MediaStreams").firstOrNull { it.str("Type").equals("Video", ignoreCase = true) }
        val label = video?.let { resolutionLabel(it.int("Width"), it.int("Height")) }
            ?: source.str("Container")?.substringBefore(',')?.uppercase().orEmpty()
        val sourceName = source.nonBlank("Name")
        // Info entries (AIOStreams statistics): nothing to play, shown as text only.
        // Also entries with neither their own id nor a path: there is nothing on the server to play.
        val infoOnly = remote == null && (source.isPlaceholderSource() ||
            (source.nonBlank("Id") == null && source.nonBlank("Path") == null && source.nonBlank("TranscodingUrl") == null))
        val url = when {
            infoOnly -> null
            remote != null && source.str("SupportsDirectPlay") != "false" -> remote
            source.str("SupportsDirectPlay") == "false" && source.str("SupportsDirectStream") == "false" &&
                source.nonBlank("TranscodingUrl") != null -> {
                val t = source.nonBlank("TranscodingUrl")!!
                val abs = if (t.startsWith("http")) t else server.address + (if (t.startsWith("/")) t else "/$t")
                if ("api_key=" in abs) abs else abs + (if ('?' in abs) "&" else "?") + "api_key=${server.token}"
            }
            remote != null -> remote
            else -> "${server.address}/Videos/$itemId/stream?static=true&MediaSourceId=${encodeUrlComponent(sourceId)}&api_key=${server.token}"
        }
        // Add-on bridges (AIOStreams formatters) put the stream's name on the first line of the
        // source name and its details below, as Stremio's name / description: show them that way,
        // as the Android app does, instead of the server name.
        val lines = sourceName?.lines()?.map { it.trimEnd() }?.filter { it.isNotBlank() }.orEmpty()
        val formatted = lines.size > 1
        val name = if (formatted) lines.first().trim()
            else listOf(server.name, label).filter { it.isNotBlank() }.joinToString("\n")
        val description = if (formatted) lines.drop(1).joinToString("\n")
            else listOfNotNull(sourceName, size?.let { "${it / 1_000_000} MB" }).joinToString(" · ").ifEmpty { null }
        return Stream(
            name = name,
            title = if (formatted) description else sourceName ?: target.str("Name"),
            description = description,
            url = url,
            ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                null, null, null, videoSize = size,
                filename = path?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('\\')
            ),
            addonName = server.name,
            addonLogo = null
        )
    }

    private fun JsonObject.isPlaceholderSource(): Boolean =
        str("Type").equals("Placeholder", ignoreCase = true)

    /** The plugin's "load when played" stand-ins, as opposed to real or informational entries. */
    private fun isStandIn(source: JsonObject): Boolean {
        val name = source.str("Name")?.lowercase().orEmpty()
        return "load when played" in name || "load the stream list" in name
    }

    private fun resolutionLabel(width: Int?, height: Int?): String? {
        val w = width ?: 0
        val h = height ?: 0
        return when {
            w >= 3200 || h >= 2000 -> "4K"
            w >= 2400 || h >= 1400 -> "1440p"
            w >= 1800 || h >= 1000 -> "1080p"
            w >= 1200 || h >= 700 -> "720p"
            w > 0 || h > 0 -> "${h}p"
            else -> null
        }
    }

    /** POST /Items/{id}/PlaybackInfo, which makes plugins resolve on-demand sources. */
    private suspend fun playbackInfo(server: MediaServer, itemId: String, sourceId: String?): JsonObject? {
        val body = buildJsonObject {
            put("UserId", server.userId)
            if (sourceId != null) put("MediaSourceId", sourceId)
            put("MaxStreamingBitrate", 400_000_000L)
            put("EnableDirectPlay", true)
            put("EnableDirectStream", true)
            put("EnableTranscoding", true)
            put("AllowVideoStreamCopy", true)
            put("AllowAudioStreamCopy", true)
            put("AutoOpenLiveStream", false)
            put("IsPlayback", true)
            put("DeviceProfile", buildJsonObject {
                put("Name", "NuvioTV")
                put("MaxStreamingBitrate", 400_000_000L)
                put("DirectPlayProfiles", buildJsonArray { add(buildJsonObject { put("Type", "Video") }) })
                put("TranscodingProfiles", buildJsonArray {
                    add(buildJsonObject {
                        put("Type", "Video"); put("Container", "ts"); put("Protocol", "hls")
                        put("VideoCodec", "h264,hevc"); put("AudioCodec", "aac,ac3,eac3")
                    })
                })
                put("SubtitleProfiles", buildJsonArray {
                    add(buildJsonObject { put("Format", "srt"); put("Method", "External") })
                    add(buildJsonObject { put("Format", "ass"); put("Method", "External") })
                })
            })
        }
        val query = buildString {
            append("UserId=").append(server.userId)
            if (sourceId != null) append("&MediaSourceId=").append(encodeUrlComponent(sourceId))
            append("&IsPlayback=true&AutoOpenLiveStream=false")
        }
        val response = http.post("${server.address}/Items/$itemId/PlaybackInfo?$query") {
            auth(server)
            setBody(TextContent(body.toString(), HttpContentType.Application.Json))
        }
        if (!response.status.isSuccess()) return null
        val info = AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: return null
        return info.takeIf { it.str("ErrorCode") == null }
    }

    // --- Ratings --------------------------------------------------------------------------

    /** A server title's id → every id it is known by (IMDb, "tmdb:…", native), for matching. */
    private val idAliases = mutableMapOf<String, Set<String>>()

    /** Other ids of a title shown from a server (empty when unknown). */
    fun aliasesFor(id: String): List<String> = idAliases[id]?.toList().orEmpty()

    /** Server-native id → the TMDB id the server stores for it (for MDBList lookups). */
    private val nativeTmdb = mutableMapOf<String, String>()

    /** The TMDB id a server keeps for a title only it knows (a "mediaserver:…" id). */
    suspend fun tmdbIdFor(id: String): String? {
        nativeTmdb[id]?.let { return it }
        val (server, itemId) = parseNative(id) ?: return null
        val tmdb = runCatching { item(server, itemId)?.obj("ProviderIds")?.nonBlank("Tmdb") }
            .onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return null
        nativeTmdb[id] = tmdb
        return tmdb
    }

    // --- Playback state (two-way) ----------------------------------------------------------

    /** contentId|videoId → the matching items on each server (looked up once). */
    private val playTargets = mutableMapOf<String, List<Pair<MediaServer, String>>>()

    /**
     * The server items for a movie or one episode. [contentId] / [imdbId] identify the title
     * (IMDb, "tmdb:…" or a server-native id), [videoId] the episode ("tt…:s:e", native, …).
     */
    private suspend fun playbackTargets(
        contentId: String, imdbId: String?, isShow: Boolean, videoId: String, season: Int?, episode: Int?
    ): List<Pair<MediaServer, String>> {
        if (servers.isEmpty()) return emptyList()
        val key = "$contentId|$videoId"
        playTargets[key]?.let { return it }
        val result: List<Pair<MediaServer, String>> = parseNative(videoId)?.takeIf { videoId != contentId || !isShow }
            ?.let { listOf(it) }
            ?: run {
                val native = parseNative(contentId)
                val imdb = (imdbId ?: contentId).substringBefore(':').takeIf { it.startsWith("tt") }
                    ?: videoId.substringBefore(':').takeIf { it.startsWith("tt") }
                val tmdb = if (contentId.startsWith("tmdb:")) contentId.split(':').getOrNull(1) else null
                val candidates = native?.let { listOf(it) } ?: servers.mapNotNull { server ->
                    runCatching { findItemId(server, imdb, tmdb, isShow) }.getOrNull()?.let { server to it }
                }
                if (!isShow) candidates
                else if (season == null || episode == null) emptyList()
                else candidates.mapNotNull { (server, seriesId) ->
                    runCatching {
                        get(server, "/Shows/$seriesId/Episodes?UserId=${server.userId}&Season=$season")
                            ?.objects("Items")?.firstOrNull { it.int("IndexNumber") == episode }?.nonBlank("Id")
                    }.getOrNull()?.let { server to it }
                }
            }
        if (result.isNotEmpty()) playTargets[key] = result
        return result
    }

    /**
     * Playback reporting, as Jellyfin / Emby clients do: "start" → /Sessions/Playing,
     * "progress" / "pause" → /Sessions/Playing/Progress, "stop" → /Sessions/Playing/Stopped (which
     * saves the resume point, or marks the item played near the end). No-op for titles the
     * servers don't have.
     */
    suspend fun reportPlayback(
        contentId: String, imdbId: String?, isShow: Boolean, videoId: String, season: Int?, episode: Int?,
        event: String, positionMs: Long, isPaused: Boolean = false
    ) {
        val targets = playbackTargets(contentId, imdbId, isShow, videoId, season, episode)
        for ((server, itemId) in targets) {
            val body = buildJsonObject {
                put("ItemId", itemId)
                put("PositionTicks", positionMs.coerceAtLeast(0) * 10_000L)
                put("CanSeek", true)
                put("IsPaused", isPaused)
                put("PlayMethod", "DirectPlay")
                if (event == "progress" || event == "pause") put("EventName", if (isPaused) "pause" else "timeupdate")
            }.toString()
            val paths = when (event) {
                "start" -> listOf("/Sessions/Playing")
                "stop" -> listOf("/Sessions/Playing", "/Sessions/Playing/Stopped").let { if (startedSessions.remove("${server.id}|$itemId")) it.drop(1) else it }
                else -> listOf("/Sessions/Playing/Progress")
            }
            for (path in paths) {
                runCatching {
                    http.post(server.address + path) {
                        auth(server)
                        setBody(TextContent(body, HttpContentType.Application.Json))
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
            if (event == "start") startedSessions += "${server.id}|$itemId"
        }
    }

    private val startedSessions = mutableSetOf<String>()

    /** Marks a movie / episode played or unplayed on every server that has it. */
    suspend fun setPlayed(
        contentId: String, imdbId: String?, isShow: Boolean, videoId: String, season: Int?, episode: Int?, played: Boolean
    ) {
        for ((server, itemId) in playbackTargets(contentId, imdbId, isShow, videoId, season, episode)) {
            runCatching {
                val url = "${server.address}/Users/${server.userId}/PlayedItems/$itemId"
                if (played) http.post(url) { auth(server) } else http.delete(url) { auth(server) }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** Series item id → IMDb id (null when the server has none), per server. */
    private val seriesImdb = mutableMapOf<String, String?>()

    private suspend fun seriesImdbId(server: MediaServer, seriesId: String): String? {
        val key = "${server.id}|$seriesId"
        if (key in seriesImdb) return seriesImdb[key]
        val ids = item(server, seriesId)?.obj("ProviderIds")
        val imdb = ids?.let { it.nonBlank("Imdb") ?: it.nonBlank("IMDB") }
        seriesImdb[key] = imdb
        return imdb
    }

    /**
     * Partly watched movies and episodes on the servers (their Continue Watching), as progress
     * entries with the same ids the app uses for those titles.
     */
    suspend fun resumeProgress(): List<WatchProgress> = servers.flatMap { server ->
        val items = runCatching {
            get(server, "/Users/${server.userId}/Items/Resume?Limit=100&Recursive=true&MediaTypes=Video" +
                "&IncludeItemTypes=Movie,Episode&Fields=ProviderIds&EnableUserData=true&EnableImages=false")
                ?.objects("Items").orEmpty()
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
        items.mapNotNull { item -> item.toResumeProgress(server) }
    }

    private suspend fun JsonObject.toResumeProgress(server: MediaServer): WatchProgress? {
        val itemId = nonBlank("Id") ?: return null
        val data = obj("UserData") ?: return null
        val position = (data.long("PlaybackPositionTicks") ?: 0L) / 10_000L
        val duration = (long("RunTimeTicks") ?: 0L) / 10_000L
        if (position <= 0 || duration <= 0) return null
        val updated = parseReleaseEpochMs(data.str("LastPlayedDate")) ?: return null
        return if (str("Type") == "Episode") {
            val seriesId = nonBlank("SeriesId") ?: return null
            val season = int("ParentIndexNumber") ?: return null
            val number = int("IndexNumber") ?: return null
            val imdb = seriesImdbId(server, seriesId)
            val contentId = imdb ?: nativeId(server, seriesId)
            WatchProgress(
                contentId = contentId,
                contentType = "series",
                videoId = imdb?.let { "$it:$season:$number" } ?: nativeId(server, itemId),
                name = str("SeriesName") ?: str("Name") ?: contentId,
                poster = "${server.address}/Items/$seriesId/Images/Primary?maxHeight=600&api_key=${server.token}",
                background = "${server.address}/Items/$seriesId/Images/Backdrop?maxWidth=1920&api_key=${server.token}",
                season = season,
                episode = number,
                episodeTitle = str("Name"),
                positionMs = position,
                durationMs = duration,
                updatedAtMs = updated,
                thumbnail = "${server.address}/Items/$itemId/Images/Primary?maxWidth=640&api_key=${server.token}"
            )
        } else {
            val ids = obj("ProviderIds")
            val imdb = ids?.let { it.nonBlank("Imdb") ?: it.nonBlank("IMDB") }
            remember(server, false, ids, itemId)
            val contentId = imdb ?: nativeId(server, itemId)
            WatchProgress(
                contentId = contentId,
                contentType = "movie",
                videoId = contentId,
                name = str("Name") ?: contentId,
                poster = "${server.address}/Items/$itemId/Images/Primary?maxHeight=600&api_key=${server.token}",
                background = "${server.address}/Items/$itemId/Images/Backdrop?maxWidth=1920&api_key=${server.token}",
                positionMs = position,
                durationMs = duration,
                updatedAtMs = updated
            )
        }
    }

    /**
     * The servers' "Next Up": for each show being watched, the next unwatched episode. Entries
     * carry no position (durationMs 0), like Trakt / Simkl up next, and the time the show was last
     * played (its series' LastPlayedDate) so they sort with Continue Watching; when the server
     * doesn't report that, they keep the server's order below everything dated.
     */
    suspend fun nextUp(): List<WatchProgress> = servers.flatMap { server ->
        val items = runCatching {
            get(server, "/Shows/NextUp?UserId=${server.userId}&Limit=50&Fields=ProviderIds" +
                "&EnableUserData=true&EnableImages=false&EnableResumable=false&EnableRewatching=false")
                ?.objects("Items").orEmpty()
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
        if (items.isEmpty()) return@flatMap emptyList()
        val seriesIds = items.mapNotNull { it.nonBlank("SeriesId") }.distinct()
        // When each show was last played (one request for all of them).
        val lastPlayed = runCatching {
            get(server, "/Users/${server.userId}/Items?Ids=${seriesIds.joinToString(",")}" +
                "&Fields=ProviderIds&EnableUserData=true&EnableImages=false")
                ?.objects("Items").orEmpty()
                .mapNotNull { series ->
                    val id = series.nonBlank("Id") ?: return@mapNotNull null
                    series.obj("ProviderIds")?.let { ids -> ids.nonBlank("Imdb") ?: ids.nonBlank("IMDB") }
                        ?.let { seriesImdb["${server.id}|$id"] = it }
                    parseReleaseEpochMs(series.obj("UserData")?.str("LastPlayedDate"))?.let { id to it }
                }.toMap()
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
        items.mapIndexedNotNull { index, item ->
            val itemId = item.nonBlank("Id") ?: return@mapIndexedNotNull null
            val seriesId = item.nonBlank("SeriesId") ?: return@mapIndexedNotNull null
            val season = item.int("ParentIndexNumber") ?: return@mapIndexedNotNull null
            val number = item.int("IndexNumber") ?: return@mapIndexedNotNull null
            val imdb = seriesImdbId(server, seriesId)
            val contentId = imdb ?: nativeId(server, seriesId)
            WatchProgress(
                contentId = contentId,
                contentType = "series",
                videoId = imdb?.let { "$it:$season:$number" } ?: nativeId(server, itemId),
                name = item.str("SeriesName") ?: contentId,
                poster = "${server.address}/Items/$seriesId/Images/Primary?maxHeight=600&api_key=${server.token}",
                background = "${server.address}/Items/$seriesId/Images/Backdrop?maxWidth=1920&api_key=${server.token}",
                season = season,
                episode = number,
                episodeTitle = item.str("Name"),
                positionMs = 0,
                durationMs = 0,
                updatedAtMs = lastPlayed[seriesId] ?: (items.size - index).toLong(),
                thumbnail = "${server.address}/Items/$itemId/Images/Primary?maxWidth=640&api_key=${server.token}"
            )
        }
    }

    /**
     * Watched keys for everything played on the servers, in the app's format: "movie:<id>" for
     * movies, "<series imdb>:<season>:<episode>" (or the native episode id) for episodes.
     */
    suspend fun playedKeys(): Set<String> = servers.flatMapTo(mutableSetOf()) { server ->
        runCatching {
            val series = get(server, "/Users/${server.userId}/Items?Recursive=true&IncludeItemTypes=Series" +
                "&Fields=ProviderIds&EnableImages=false&EnableUserData=false")
                ?.objects("Items").orEmpty()
                .mapNotNull { s -> s.nonBlank("Id")?.let { id -> id to s.obj("ProviderIds")?.let { it.nonBlank("Imdb") ?: it.nonBlank("IMDB") } } }
                .toMap()
            series.forEach { (id, imdb) -> seriesImdb["${server.id}|$id"] = imdb }
            get(server, "/Users/${server.userId}/Items?Recursive=true&Filters=IsPlayed&IncludeItemTypes=Movie,Episode" +
                "&Fields=ProviderIds&EnableImages=false")
                ?.objects("Items").orEmpty()
                .mapNotNull { item ->
                    val id = item.nonBlank("Id") ?: return@mapNotNull null
                    if (item.str("Type") == "Episode") {
                        val season = item.int("ParentIndexNumber")
                        val number = item.int("IndexNumber")
                        val imdb = item.nonBlank("SeriesId")?.let { series[it] }
                        if (imdb != null && season != null && number != null) "$imdb:$season:$number" else nativeId(server, id)
                    } else {
                        val imdb = item.obj("ProviderIds")?.let { it.nonBlank("Imdb") ?: it.nonBlank("IMDB") }
                        "movie:${imdb ?: nativeId(server, id)}"
                    }
                }
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
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
        const val MAX_SOURCE_PROBES = 3
    }
}
