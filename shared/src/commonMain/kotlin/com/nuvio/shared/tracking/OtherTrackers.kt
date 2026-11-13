package com.nuvio.shared.tracking

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.int
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

/** Simkl: PIN sign-in, scrobble and history (simkl-api-key = client id). */
class SimklService(
    private val http: HttpClient,
    private val store: KeyValueStore,
    private val clientId: String
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
    val isConnected: Boolean get() = store.readToken(KEY) != null
    val username: String? get() = store.readToken(KEY)?.username

    @Throws(Exception::class)
    suspend fun startSignIn(): DeviceSignIn {
        val response = http.get("$API/oauth/pin?client_id=${encodeUrlComponent(clientId)}") { header("simkl-api-key", clientId) }
        val body = AddonJson.parseToJsonElement(response.bodyAsText()).asObject()
            ?: throw TrackerException("Simkl sign-in failed")
        val code = body.nonBlank("user_code") ?: throw TrackerException("Simkl returned no code")
        return DeviceSignIn("simkl", code, body.nonBlank("verification_url") ?: "https://simkl.com/pin", code, body.int("interval") ?: 5)
    }

    @Throws(Exception::class)
    suspend fun pollSignIn(signIn: DeviceSignIn): Boolean {
        val response = http.get("$API/oauth/pin/${encodeUrlComponent(signIn.userCode)}?client_id=${encodeUrlComponent(clientId)}") {
            header("simkl-api-key", clientId)
        }
        if (!response.status.isSuccess()) return false
        val body = AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: return false
        val token = body.nonBlank("access_token") ?: return false
        store.writeToken(KEY, StoredToken(token))
        runCatching {
            val settings = AddonJson.parseToJsonElement(
                http.post("$API/users/settings") { headers(token) }.bodyAsText()
            ).asObject()
            val name = settings?.obj("user")?.nonBlank("name") ?: settings?.obj("account")?.nonBlank("id")
            if (name != null) store.writeToken(KEY, StoredToken(token, username = name))
        }
        return true
    }

    fun signOut() = store.writeToken(KEY, null)

    suspend fun scrobble(media: TrackedMedia, action: ScrobbleAction, progressPercent: Double) {
        val token = store.readToken(KEY)?.accessToken ?: return
        val payload = body(media)?.let { JsonObject(it + ("progress" to kotlinx.serialization.json.JsonPrimitive(progressPercent))) } ?: return
        runCatching {
            http.post("$API/scrobble/${action.path}") {
                headers(token)
                setBody(TextContent(payload.toString(), ContentType.Application.Json))
            }
        }
    }

    suspend fun markWatched(media: TrackedMedia) {
        val token = store.readToken(KEY)?.accessToken ?: return
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
            http.post("$API/sync/history") {
                headers(token)
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

    private fun io.ktor.client.request.HttpRequestBuilder.headers(token: String) {
        header("simkl-api-key", clientId)
        header("Authorization", "Bearer $token")
    }

    private companion object {
        const val API = "https://api.simkl.com"
        const val KEY = "simkl.token"
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
