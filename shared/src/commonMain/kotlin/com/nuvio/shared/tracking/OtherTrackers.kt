package com.nuvio.shared.tracking

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.int
import com.nuvio.shared.data.long
import com.nuvio.shared.data.nonBlank
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.objects
import com.nuvio.shared.data.str
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.encodeUrlComponent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Simkl over AUTH V2 (RFC 8628 device flow), as the Android SimklAuthRepository / SimklApiClient:
 * POST /oauth2/device, poll POST /oauth2/token, refresh tokens; every request carries the
 * client_id / app-name / app-version query parameters.
 */
class SimklService(
    private val http: HttpClient,
    private val store: KeyValueStore,
    private val clientId: String,
    private val appName: String = "nuvio",
    private val appVersion: String = "0.1.0"
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
    val isConnected: Boolean get() = store.readToken(KEY) != null
    val username: String? get() = store.readToken(KEY)?.username

    private fun url(path: String): String =
        "$API$path?client_id=${encodeUrlComponent(clientId)}&app-name=${encodeUrlComponent(appName)}" +
            "&app-version=${encodeUrlComponent(appVersion)}"

    private fun io.ktor.client.request.HttpRequestBuilder.baseHeaders() {
        header("User-Agent", "NuvioTV/$appVersion")
        header("Accept", "application/json")
    }

    private fun form(vararg fields: Pair<String, String>) = TextContent(
        fields.joinToString("&") { (k, v) -> "${encodeUrlComponent(k)}=${encodeUrlComponent(v)}" },
        ContentType.Application.FormUrlEncoded
    )

    @Throws(Exception::class)
    suspend fun startSignIn(): DeviceSignIn {
        if (!isConfigured) throw TrackerException("This build has no Simkl client id")
        val response = http.post(url("/oauth2/device")) {
            baseHeaders()
            setBody(form("client_id" to clientId, "scope" to "media:read media:write"))
        }
        val body = runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asObject() }.getOrNull()
        if (!response.status.isSuccess() || body == null) {
            throw TrackerException(body?.str("error_description") ?: body?.str("error") ?: "Simkl sign-in failed (HTTP ${response.status.value})")
        }
        val deviceCode = body.nonBlank("device_code") ?: throw TrackerException("Simkl returned no device code")
        val userCode = body.nonBlank("user_code") ?: throw TrackerException("Simkl returned no code")
        return DeviceSignIn(
            providerId = "simkl",
            userCode = userCode,
            verificationUrl = body.nonBlank("verification_uri_complete") ?: body.nonBlank("verification_uri") ?: "https://simkl.com/pin",
            deviceCode = deviceCode,
            intervalSeconds = body.int("interval") ?: 5
        )
    }

    /** True once approved (tokens stored); false while pending or asked to slow down. */
    @Throws(Exception::class)
    suspend fun pollSignIn(signIn: DeviceSignIn): Boolean {
        val response = http.post(url("/oauth2/token")) {
            baseHeaders()
            setBody(form(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId,
                "device_code" to signIn.deviceCode
            ))
        }
        val body = runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asObject() }.getOrNull()
        if (!response.status.isSuccess()) {
            when (body?.str("error")) {
                "authorization_pending", "slow_down", null -> return false
                "expired_token" -> throw TrackerException("The code expired; start again")
                "access_denied" -> throw TrackerException("Sign-in was declined")
                "invalid_grant" -> throw TrackerException("The code is no longer valid; start again")
                "invalid_client", "unauthorized_client" -> throw TrackerException("Simkl rejected this app's client id")
                else -> return if (response.status.value == 429 || response.status.value >= 500) false
                    else throw TrackerException(body.str("error_description") ?: "Simkl sign-in failed")
            }
        }
        storeTokens(body ?: return false, previous = null)
        runCatching { fetchUsername() }
        return true
    }

    fun signOut() = store.writeToken(KEY, null)

    private fun storeTokens(body: JsonObject, previous: StoredToken?) {
        val access = body.nonBlank("access_token") ?: return
        val expiresIn = body.long("expires_in") ?: (7 * 24 * 3600L)
        store.writeToken(KEY, StoredToken(
            accessToken = access,
            refreshToken = body.nonBlank("refresh_token") ?: previous?.refreshToken,
            expiresAtMs = com.nuvio.shared.util.currentTimeMillis() + expiresIn * 1000,
            username = previous?.username
        ))
    }

    private suspend fun accessToken(): String? {
        val token = store.readToken(KEY) ?: return null
        val refresh = token.refreshToken
        if (!token.isExpired || refresh == null) return token.accessToken
        val response = http.post(url("/oauth2/token")) {
            baseHeaders()
            setBody(form("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh))
        }
        val body = runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asObject() }.getOrNull()
        when {
            response.status.isSuccess() && body != null -> storeTokens(body, token)
            response.status.value == 400 || response.status.value == 401 -> { signOut(); return null }
        }
        return store.readToken(KEY)?.accessToken
    }

    private suspend fun fetchUsername() {
        val token = accessToken() ?: return
        val settings = AddonJson.parseToJsonElement(
            http.post(url("/users/settings")) { baseHeaders(); header("Authorization", "Bearer $token") }.bodyAsText()
        ).asObject()
        val name = settings?.obj("user")?.nonBlank("name") ?: settings?.obj("account")?.str("id")
        store.readToken(KEY)?.let { if (name != null) store.writeToken(KEY, it.copy(username = name)) }
    }

    suspend fun scrobble(media: TrackedMedia, action: ScrobbleAction, progressPercent: Double) {
        val token = accessToken() ?: return
        val payload = body(media)?.let { JsonObject(it + ("progress" to kotlinx.serialization.json.JsonPrimitive(progressPercent))) } ?: return
        runCatching {
            http.post(url("/scrobble/${action.path}")) {
                baseHeaders()
                header("Authorization", "Bearer $token")
                setBody(TextContent(payload.toString(), ContentType.Application.Json))
            }
        }
    }

    suspend fun markWatched(media: TrackedMedia) {
        val token = accessToken() ?: return
        val ids = ids(media) ?: return
        val payload = if (media.isEpisode) buildJsonObject {
            putJsonArray("shows") {
                add(buildJsonObject {
                    put("ids", ids)
                    putJsonArray("seasons") {
                        add(buildJsonObject {
                            put("number", media.season ?: 0)
                            putJsonArray("episodes") { add(buildJsonObject { put("number", media.episode ?: 0) }) }
                        })
                    }
                })
            }
        } else buildJsonObject { putJsonArray("movies") { add(buildJsonObject { put("ids", ids) }) } }
        runCatching {
            http.post(url("/sync/history")) {
                baseHeaders()
                header("Authorization", "Bearer $token")
                setBody(TextContent(payload.toString(), ContentType.Application.Json))
            }
        }
    }

    /** Marks (or with [remove], unmarks) many episodes of one show in a single request. */
    suspend fun markEpisodesWatched(show: TrackedMedia, episodes: List<Pair<Int, Int>>, remove: Boolean = false) {
        if (episodes.isEmpty()) return
        val ids = ids(show) ?: return
        val payload = buildJsonObject {
            putJsonArray("shows") {
                add(buildJsonObject {
                    put("ids", ids)
                    putJsonArray("seasons") {
                        episodes.groupBy({ it.first }, { it.second }).forEach { (season, numbers) ->
                            add(buildJsonObject {
                                put("number", season)
                                putJsonArray("episodes") { numbers.distinct().forEach { add(buildJsonObject { put("number", it) }) } }
                            })
                        }
                    }
                })
            }
        }
        val token = accessToken() ?: return
        runCatching {
            http.post(url(if (remove) "/sync/history/remove" else "/sync/history")) {
                baseHeaders()
                header("Authorization", "Bearer $token")
                setBody(TextContent(payload.toString(), ContentType.Application.Json))
            }
        }
    }

    /**
     * Simkl "completed" lists: finished movies become watched movie keys, finished shows and
     * anime become "series:" keys. Anime entries are per season on Simkl, so they only use the
     * per-season ids (kitsu / mal / anilist), never the show-wide imdb id.
     */
    @Throws(Exception::class)
    suspend fun completedItems(): TrackerWatched {
        val token = accessToken() ?: return TrackerWatched()
        val keys = mutableListOf<String>()
        val completed = mutableSetOf<String>()
        for (type in listOf("movies", "shows", "anime")) {
            val response = http.get(url("/sync/all-items/$type/completed")) {
                baseHeaders()
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) continue
            val root = runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asObject() }.getOrNull() ?: continue
            root.objects(type).forEach { entry ->
                val item = entry.obj(if (type == "movies") "movie" else "show") ?: return@forEach
                val ids = item.obj("ids") ?: return@forEach
                val prefix = if (type == "movies") "movie" else "series"
                val idKeys = buildList {
                    if (type != "anime") {
                        ids.nonBlank("imdb")?.let { add(it) }
                        ids.idString("tmdb")?.let { add("tmdb:$it") }
                    }
                    ids.idString("kitsu")?.let { add("kitsu:$it") }
                    ids.idString("mal")?.let { add("mal:$it") }
                    ids.idString("anilist")?.let { add("anilist:$it") }
                }
                if (prefix == "movie") keys += idKeys.map { "movie:$it" } else completed += idKeys.map { "series:$it" }
            }
        }
        return TrackerWatched(keys, completed)
    }

    private fun JsonObject.idString(key: String): String? =
        nonBlank(key) ?: int(key)?.toString()

    private fun ids(media: TrackedMedia): JsonObject? {
        if (media.imdbId == null && media.tmdbId == null && media.kitsuId == null) return null
        return buildJsonObject {
            media.imdbId?.let { put("imdb", it) }
            media.tmdbId?.let { put("tmdb", it) }
            media.kitsuId?.let { put("kitsu", it) }
        }
    }

    private fun body(media: TrackedMedia): Map<String, kotlinx.serialization.json.JsonElement>? {
        val ids = ids(media) ?: return null
        return if (media.isEpisode) mapOf(
            "show" to buildJsonObject { put("ids", ids); put("title", media.title) },
            "episode" to buildJsonObject { put("season", media.season ?: 0); put("number", media.episode ?: 0) }
        ) else mapOf("movie" to buildJsonObject { put("ids", ids); put("title", media.title) })
    }

    private companion object {
        const val API = "https://api.simkl.com"
        // V2 tokens; an old V1 PIN token lives under "simkl.token" and is ignored.
        const val KEY = "simkl.token.v2"
    }
}

/**
 * AniList: token sign-in (PIN redirect: the site shows a token the user pastes, e.g. from
 * their iPhone via the Apple TV keyboard), progress updates through GraphQL. Stremio ids are
 * mapped to AniList ids with arm.haglund.dev, like the Android AniListIdResolver.
 */
class AniListService(
    private val http: HttpClient,
    private val store: KeyValueStore,
    private val clientId: String
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
    val isConnected: Boolean get() = store.readToken(KEY) != null
    val username: String? get() = store.readToken(KEY)?.username

    val authorizeUrl: String
        get() = "https://anilist.co/api/v2/oauth/authorize?client_id=${encodeUrlComponent(clientId)}&response_type=token"

    @Throws(Exception::class)
    suspend fun signIn(token: String) {
        val trimmed = token.trim().substringAfter("access_token=").substringBefore('&')
        val viewer = graphql(trimmed, "query { Viewer { id name } }")?.obj("data")?.obj("Viewer")
            ?: throw TrackerException("AniList didn't accept that token")
        store.writeToken(KEY, StoredToken(trimmed, username = viewer.str("name")))
    }

    fun signOut() = store.writeToken(KEY, null)

    /** Sets the episode progress (and CURRENT / COMPLETED status) for an anime episode. */
    suspend fun updateProgress(media: TrackedMedia, completedTitle: Boolean) {
        val token = store.readToken(KEY)?.accessToken ?: return
        val aniListId = resolveAniListId(media) ?: return
        val progress = media.episode ?: 1
        val status = if (completedTitle) "COMPLETED" else "CURRENT"
        graphql(
            token,
            "mutation(\$id:Int,\$p:Int,\$s:MediaListStatus){SaveMediaListEntry(mediaId:\$id,progress:\$p,status:\$s){id}}",
            buildJsonObject { put("id", aniListId); put("p", progress); put("s", status) }
        )
    }

    /**
     * Anime marked COMPLETED on AniList: movies as watched keys, everything else as "series:"
     * keys, by AniList, MAL and (via arm.haglund.dev) Kitsu id, since AniList entries are per season.
     */
    @Throws(Exception::class)
    suspend fun completedItems(): TrackerWatched {
        val token = store.readToken(KEY)?.accessToken ?: return TrackerWatched()
        val userId = graphql(token, "query { Viewer { id } }")?.obj("data")?.obj("Viewer")?.int("id")
            ?: return TrackerWatched()
        val data = graphql(
            token,
            "query(\$u:Int){MediaListCollection(userId:\$u,type:ANIME,status:COMPLETED){lists{entries{media{id idMal format}}}}}",
            buildJsonObject { put("u", userId) }
        )?.obj("data")?.obj("MediaListCollection") ?: return TrackerWatched()
        val media = data.objects("lists").flatMap { it.objects("entries") }.mapNotNull { it.obj("media") }
        val kitsuByAniList = kitsuIds(media.mapNotNull { it.int("id") })
        val keys = mutableListOf<String>()
        val completed = mutableSetOf<String>()
        media.forEach { m ->
            val id = m.int("id") ?: return@forEach
            val ids = listOfNotNull("anilist:$id", m.int("idMal")?.let { "mal:$it" }, kitsuByAniList[id]?.let { "kitsu:$it" })
            if (m.str("format") == "MOVIE") keys += ids.map { "movie:$it" } else completed += ids.map { "series:$it" }
        }
        return TrackerWatched(keys, completed)
    }

    /** AniList id → Kitsu id, in batches of 100 (arm.haglund.dev bulk lookup). */
    private suspend fun kitsuIds(aniListIds: List<Int>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        aniListIds.distinct().chunked(100).forEach { chunk ->
            val body = buildJsonArray { chunk.forEach { add(buildJsonObject { put("anilist", it) }) } }
            val response = runCatching {
                http.post("https://arm.haglund.dev/api/v2/ids") {
                    header("Accept", "application/json")
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
            }.getOrNull() ?: return@forEach
            if (!response.status.isSuccess()) return@forEach
            val rows = runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asArray() }.getOrNull() ?: return@forEach
            rows.forEachIndexed { index, row ->
                val obj = row.asObject() ?: return@forEachIndexed
                val kitsu = obj.int("kitsu") ?: return@forEachIndexed
                result[obj.int("anilist") ?: chunk.getOrNull(index) ?: return@forEachIndexed] = kitsu.toString()
            }
        }
        return result
    }

    private suspend fun resolveAniListId(media: TrackedMedia): Int? {
        val (source, id) = when {
            media.kitsuId != null -> "kitsu" to media.kitsuId
            media.imdbId != null -> "imdb" to media.imdbId
            else -> return null
        }
        val cacheKey = "anilist.map.$source.$id"
        store.getString(cacheKey)?.toIntOrNull()?.let { return it }
        val response = runCatching { http.get("https://arm.haglund.dev/api/v2/ids?source=$source&id=${encodeUrlComponent(id)}") }.getOrNull()
            ?: return null
        if (!response.status.isSuccess()) return null
        val element = runCatching { AddonJson.parseToJsonElement(response.bodyAsText()) }.getOrNull()
        val obj = element.asObject() ?: element.asArray()?.firstOrNull().asObject()
        val result = obj?.int("anilist") ?: return null
        store.putString(cacheKey, result.toString())
        return result
    }

    private suspend fun graphql(token: String, query: String, variables: JsonObject? = null): JsonObject? {
        val payload = buildJsonObject {
            put("query", query)
            variables?.let { put("variables", it) }
        }
        val response = runCatching {
            http.post("https://graphql.anilist.co") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/json")
                setBody(TextContent(payload.toString(), ContentType.Application.Json))
            }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return AddonJson.parseToJsonElement(response.bodyAsText()).asObject()
    }

    private companion object { const val KEY = "anilist.token" }
}

/**
 * One rating from MDBList. [icon] names the logo in the tvOS asset catalog (the Android
 * drawables), including Rotten Tomatoes fresh / certified / rotten and Popcornmeter states.
 */
data class ExternalRating(val source: String, val label: String, val value: String, val icon: String)

/** MDBList ratings by IMDb id (API key from Settings). Port of MDBListRatingDtos + MDBListRatingsRow. */
class MdbListService(private val http: HttpClient) {
    @Throws(Exception::class)
    suspend fun ratings(apiKey: String, imdbId: String, isShow: Boolean): List<ExternalRating> {
        if (apiKey.isBlank() || !imdbId.startsWith("tt")) return emptyList()
        val type = if (isShow) "show" else "movie"
        val response = http.get(
            "https://api.mdblist.com/imdb/$type/$imdbId?apikey=${encodeUrlComponent(apiKey)}&append_to_response=keyword"
        )
        if (!response.status.isSuccess()) return emptyList()
        val body = AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: return emptyList()
        val keywords = body.objects("keywords").mapNotNull { it.str("name")?.lowercase() }.toSet()
        val values = LinkedHashMap<String, Double>()
        body.objects("ratings").forEach { r ->
            val source = when (val raw = r.str("source")?.lowercase()) {
                "popcorn", "audience", "tomatoesaudience" -> "audience"
                "myanimelist", "mal" -> "mal"
                "imdb", "trakt", "tmdb", "letterboxd", "tomatoes", "metacritic" -> raw
                else -> return@forEach
            }
            val maximum = when (source) {
                "imdb", "mal" -> 10.0
                "letterboxd" -> 5.0
                else -> 100.0
            }
            val raw = r.str("value")?.toDoubleOrNull()?.takeIf { it >= 0 } ?: return@forEach
            val score = r.str("score")?.toDoubleOrNull()
            val value = if (source == "letterboxd" && score != null) score / 20.0 else raw
            if (value !in 0.0..maximum || value == 0.0) return@forEach
            if (source !in values) values[source] = value
        }
        return ORDER.mapNotNull { source ->
            val value = values[source] ?: return@mapNotNull null
            ExternalRating(source, LABELS.getValue(source), format(source, value), icon(source, value, keywords))
        }
    }

    private fun icon(source: String, value: Double, keywords: Set<String>): String = when (source) {
        "tomatoes" -> when {
            "certified-fresh" in keywords && value >= 70 -> "rating_tomatoes_certified"
            value >= 60 -> "rating_tomatoes"
            else -> "rating_tomatoes_rotten"
        }
        "audience" -> when {
            "certified-hot" in keywords && value >= 80 -> "rating_audience_verified_hot"
            value >= 60 -> "rating_audience"
            else -> "rating_audience_stale"
        }
        else -> "rating_$source"
    }

    private fun format(source: String, value: Double): String {
        val oneDecimal = kotlin.math.round(value * 10).toInt().let { "${it / 10}.${it % 10}" }
        return when (source) {
            "imdb", "letterboxd", "mal" -> oneDecimal
            "tmdb" -> if (value > 10) "${kotlin.math.round(value).toInt()}%" else oneDecimal
            "tomatoes", "audience" -> "${kotlin.math.round(value).toInt()}%"
            else -> if (value % 1.0 == 0.0) value.toInt().toString() else oneDecimal
        }
    }

    private companion object {
        /** MDBListSettings.DEFAULT_RATING_ORDER */
        val ORDER = listOf("imdb", "trakt", "tmdb", "tomatoes", "audience", "letterboxd", "metacritic", "mal")
        val LABELS = mapOf(
            "imdb" to "IMDb", "trakt" to "Trakt", "tmdb" to "TMDB", "tomatoes" to "Rotten Tomatoes",
            "audience" to "Popcornmeter", "letterboxd" to "Letterboxd", "metacritic" to "Metacritic", "mal" to "MyAnimeList"
        )
    }
}
