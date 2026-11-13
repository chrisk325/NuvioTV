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
import com.nuvio.shared.model.MetaPreview
import com.nuvio.shared.model.PosterShape
import com.nuvio.shared.model.Stream
import com.nuvio.shared.model.StreamBehaviorHints
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import com.nuvio.shared.util.encodeUrlComponent
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType as HttpContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
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

data class ServerLibrary(val server: MediaServer, val id: String, val name: String) {
    val key: String get() = "${server.id}:$id"
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
            .mapNotNull { o -> ServerLibrary(server, o.nonBlank("Id") ?: return@mapNotNull null, o.str("Name").orEmpty()) }

    /** Newest titles of a library as previews; titles with an IMDb id open normal add-on details. */
    @Throws(Exception::class)
    suspend fun latest(library: ServerLibrary, start: Int, limit: Int): List<MetaPreview> =
        get(
            library.server,
            "/Users/${library.server.userId}/Items?ParentId=${library.id}&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&SortBy=DateCreated&SortOrder=Descending&Fields=ProviderIds,Overview,ProductionYear&StartIndex=$start&Limit=$limit"
        )?.objects("Items").orEmpty().mapNotNull { it.toPreview(library.server) }

    private fun JsonObject.toPreview(server: MediaServer): MetaPreview? {
        val imdb = obj("ProviderIds")?.let { it.nonBlank("Imdb") ?: it.nonBlank("IMDB") } ?: return null
        val isShow = str("Type") == "Series"
        val itemId = nonBlank("Id") ?: return null
        return MetaPreview(
            id = imdb,
            type = if (isShow) ContentType.SERIES else ContentType.MOVIE,
            name = str("Name") ?: imdb,
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

    /**
     * Copies of a title on the user's servers, as streams (direct play; mpv plays every container).
     * [videoId] is "tt…" for movies or "tt…:season:episode".
     */
    suspend fun streams(type: String, videoId: String): List<AddonStreams> {
        val parts = videoId.split(':')
        val imdb = parts.firstOrNull()?.takeIf { it.startsWith("tt") } ?: return emptyList()
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        return servers.mapNotNull { server ->
            try {
                val itemType = if (season != null) "Series" else "Movie"
                val item = get(server, "/Users/${server.userId}/Items?Recursive=true&IncludeItemTypes=$itemType&AnyProviderIdEquals=${encodeUrlComponent("imdb.$imdb")}&Fields=ProviderIds,MediaSources&Limit=5")
                    ?.objects("Items")?.firstOrNull { o ->
                        o.obj("ProviderIds")?.let { (it.str("Imdb") ?: it.str("IMDB")).equals(imdb, ignoreCase = true) } == true
                    } ?: return@mapNotNull null
                val target = if (season != null && episode != null) {
                    get(server, "/Shows/${item.nonBlank("Id")}/Episodes?UserId=${server.userId}&Season=$season&Fields=MediaSources")
                        ?.objects("Items")?.firstOrNull { it.int("IndexNumber") == episode } ?: return@mapNotNull null
                } else item
                val itemId = target.nonBlank("Id") ?: return@mapNotNull null
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
                AddonStreams(server.name, null, streams)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
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

    private companion object { const val KEY = "mediaservers.v1" }
}
