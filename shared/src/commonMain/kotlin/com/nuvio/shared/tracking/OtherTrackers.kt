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

/** One rating from MDBList (IMDb, Trakt, Letterboxd, Tomatoes, Metacritic, TMDB…). */
data class ExternalRating(val source: String, val label: String, val value: String)

/** MDBList ratings by IMDb id (API key from Settings). */
class MdbListService(private val http: HttpClient) {
    @Throws(Exception::class)
    suspend fun ratings(apiKey: String, imdbId: String, isShow: Boolean): List<ExternalRating> {
        if (apiKey.isBlank() || !imdbId.startsWith("tt")) return emptyList()
        val type = if (isShow) "show" else "movie"
        val response = http.get("https://api.mdblist.com/imdb/$type/$imdbId?apikey=${encodeUrlComponent(apiKey)}")
        if (!response.status.isSuccess()) return emptyList()
        val body = AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: return emptyList()
        return body.objects("ratings").mapNotNull { r ->
            val source = r.nonBlank("source") ?: return@mapNotNull null
            val raw = r.str("value")?.takeIf { it != "null" } ?: return@mapNotNull null
            val number = raw.toDoubleOrNull() ?: return@mapNotNull null
            if (number <= 0) return@mapNotNull null
            val label = LABELS[source] ?: source.replaceFirstChar { it.uppercase() }
            val text = when {
                source in PERCENT_SOURCES -> "${number.toInt()}%"
                number > 10 -> "${number.toInt()}"
                else -> formatOne(number)
            }
            ExternalRating(source, label, text)
        }
    }

    private fun formatOne(value: Double): String {
        val tenths = kotlin.math.round(value * 10).toInt()
        return "${tenths / 10}.${tenths % 10}"
    }

    private companion object {
        val PERCENT_SOURCES = setOf("tomatoes", "popcorn", "metacritic")
        val LABELS = mapOf(
            "imdb" to "IMDb", "trakt" to "Trakt", "tmdb" to "TMDB", "letterboxd" to "Letterboxd",
            "tomatoes" to "Rotten Tomatoes", "popcorn" to "Audience", "metacritic" to "Metacritic",
            "metacriticuser" to "Metacritic user", "rogerebert" to "Roger Ebert", "myanimelist" to "MAL"
        )
    }
}
