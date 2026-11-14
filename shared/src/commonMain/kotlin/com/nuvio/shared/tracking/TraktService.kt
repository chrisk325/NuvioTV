package com.nuvio.shared.tracking

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.int
import com.nuvio.shared.data.long
import com.nuvio.shared.data.nonBlank
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.objects
import com.nuvio.shared.data.str
import com.nuvio.shared.model.ContentType
import com.nuvio.shared.model.MetaPreview
import com.nuvio.shared.model.PosterShape
import com.nuvio.shared.model.WatchProgress
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import com.nuvio.shared.util.parseReleaseEpochMs
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType as HttpContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class TrackerException(message: String) : Exception(message)

/**
 * Trakt: device-code sign-in, scrobbling, watched history, playback progress and watchlist.
 * Port of the Android TraktApi usage (same endpoints, api version 2).
 */
class TraktService(
    private val http: HttpClient,
    private val store: KeyValueStore,
    private val clientId: String,
    private val clientSecret: String
) {
    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val isConnected: Boolean get() = store.readToken(TOKEN_KEY) != null
    val username: String? get() = store.readToken(TOKEN_KEY)?.username

    // --- Auth -------------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun startSignIn(): DeviceSignIn {
        val body = postJson("oauth/device/code", """{"client_id":"$clientId"}""", auth = false)
            ?: throw TrackerException("Trakt sign-in failed")
        return DeviceSignIn(
            providerId = "trakt",
            userCode = body.nonBlank("user_code") ?: throw TrackerException("Trakt returned no code"),
            verificationUrl = body.nonBlank("verification_url") ?: "https://trakt.tv/activate",
            deviceCode = body.nonBlank("device_code") ?: throw TrackerException("Trakt returned no device code"),
            intervalSeconds = body.int("interval") ?: 5
        )
    }

    /** True once approved (token stored); false while still pending. */
    @Throws(Exception::class)
    suspend fun pollSignIn(signIn: DeviceSignIn): Boolean {
        val response = http.post("$API/oauth/device/token") {
            traktHeaders(auth = false)
            setBody(json("""{"code":"${signIn.deviceCode}","client_id":"$clientId","client_secret":"$clientSecret"}"""))
        }
        when (response.status.value) {
            200 -> Unit
            400 -> return false // pending
            404 -> throw TrackerException("Invalid code")
            409 -> throw TrackerException("Code already used")
            410 -> throw TrackerException("Code expired, start again")
            418 -> throw TrackerException("Sign-in was denied")
            429 -> return false // polling too fast
            else -> return false
        }
        storeToken(AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: return false)
        runCatching { fetchUsername() }
        return true
    }

    fun signOut() {
        store.writeToken(TOKEN_KEY, null)
    }

    private fun storeToken(body: JsonObject, username: String? = null) {
        val access = body.nonBlank("access_token") ?: return
        val created = body.long("created_at") ?: (currentTimeMillis() / 1000)
        val expires = body.long("expires_in") ?: 0
        store.writeToken(
            TOKEN_KEY,
            StoredToken(access, body.nonBlank("refresh_token"), (created + expires) * 1000, username ?: this.username)
        )
    }

    private suspend fun fetchUsername() {
        val body = get("users/settings")?.asObject() ?: return
        val name = body.obj("user")?.nonBlank("username") ?: return
        store.readToken(TOKEN_KEY)?.let { store.writeToken(TOKEN_KEY, it.copy(username = name)) }
    }

    private suspend fun accessToken(): String? {
        val token = store.readToken(TOKEN_KEY) ?: return null
        if (!token.isExpired || token.refreshToken == null) return token.accessToken
        val body = postJson(
            "oauth/token",
            """{"refresh_token":"${token.refreshToken}","client_id":"$clientId","client_secret":"$clientSecret",""" +
                """"redirect_uri":"urn:ietf:wg:oauth:2.0:oob","grant_type":"refresh_token"}""",
            auth = false
        ) ?: return token.accessToken
        storeToken(body, token.username)
        return store.readToken(TOKEN_KEY)?.accessToken
    }

    // --- Scrobble / history -----------------------------------------------------------------

    suspend fun scrobble(media: TrackedMedia, action: ScrobbleAction, progressPercent: Double) {
        if (!isConnected) return
        val body = mediaBody(media) ?: return
        val payload = JsonObject(body + ("progress" to kotlinx.serialization.json.JsonPrimitive(progressPercent.coerceIn(0.0, 100.0))))
        postJson("scrobble/${action.path}", payload.toString())
    }

    suspend fun markWatched(media: TrackedMedia) {
        if (!isConnected) return
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
        } else buildJsonObject {
            putJsonArray("movies") { add(buildJsonObject { put("ids", ids) }) }
        }
        postJson("sync/history", payload.toString())
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
        if (!isConnected) return
        postJson(if (remove) "sync/history/remove" else "sync/history", payload.toString())
    }

    /** Watched keys in WatchedRepository format: "movie:tt…" and "tt…:s:e". */
    @Throws(Exception::class)
    suspend fun watchedKeys(): List<String> {
        val keys = mutableListOf<String>()
        (get("sync/watched/movies") as? JsonArray)?.forEach { entry ->
            entry.asObject()?.obj("movie")?.obj("ids")?.nonBlank("imdb")?.let { keys += "movie:$it" }
        }
        (get("sync/watched/shows") as? JsonArray)?.forEach { entry ->
            val o = entry.asObject() ?: return@forEach
            val imdb = o.obj("show")?.obj("ids")?.nonBlank("imdb") ?: return@forEach
            o.objects("seasons").forEach { season ->
                val s = season.int("number") ?: return@forEach
                season.objects("episodes").forEach { ep -> ep.int("number")?.let { keys += "$imdb:$s:$it" } }
            }
        }
        return keys
    }

    /** In-progress items from Trakt (sync/playback) mapped to local progress entries. */
    @Throws(Exception::class)
    suspend fun playbackProgress(): List<WatchProgress> {
        val items = get("sync/playback") as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val o = element.asObject() ?: return@mapNotNull null
            val percent = o.str("progress")?.toDoubleOrNull() ?: return@mapNotNull null
            val updated = parseReleaseEpochMs(o.str("paused_at")) ?: currentTimeMillis()
            // Trakt has no runtime here; assume 100 "units" so the fraction is right.
            val duration = 6_000_000L
            when (o.str("type")) {
                "movie" -> {
                    val movie = o.obj("movie") ?: return@mapNotNull null
                    val imdb = movie.obj("ids")?.nonBlank("imdb") ?: return@mapNotNull null
                    WatchProgress(
                        contentId = imdb, contentType = "movie", videoId = imdb,
                        name = movie.str("title") ?: imdb, poster = poster(imdb), background = background(imdb),
                        positionMs = (duration * percent / 100).toLong(), durationMs = duration, updatedAtMs = updated
                    )
                }
                "episode" -> {
                    val show = o.obj("show") ?: return@mapNotNull null
                    val ep = o.obj("episode") ?: return@mapNotNull null
                    val imdb = show.obj("ids")?.nonBlank("imdb") ?: return@mapNotNull null
                    val s = ep.int("season") ?: return@mapNotNull null
                    val e = ep.int("number") ?: return@mapNotNull null
                    WatchProgress(
                        contentId = imdb, contentType = "series", videoId = "$imdb:$s:$e",
                        name = show.str("title") ?: imdb, poster = poster(imdb), background = background(imdb),
                        season = s, episode = e, episodeTitle = ep.str("title"),
                        positionMs = (duration * percent / 100).toLong(), durationMs = duration, updatedAtMs = updated
                    )
                }
                else -> null
            }
        }
    }

    // --- Watchlist --------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun watchlist(): List<MetaPreview> {
        val items = get("sync/watchlist?sort_by=added&sort_how=desc") as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val o = element.asObject() ?: return@mapNotNull null
            val isMovie = o.str("type") == "movie"
            val item = o.obj(if (isMovie) "movie" else "show") ?: return@mapNotNull null
            val imdb = item.obj("ids")?.nonBlank("imdb") ?: return@mapNotNull null
            MetaPreview(
                id = imdb,
                type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
                name = item.str("title") ?: imdb,
                poster = poster(imdb),
                posterShape = PosterShape.POSTER,
                background = background(imdb),
                logo = null,
                description = null,
                releaseInfo = item.int("year")?.toString(),
                imdbRating = null,
                genres = emptyList(),
                imdbId = imdb
            )
        }
    }

    suspend fun setShowInWatchlist(imdbId: String, isShow: Boolean, inWatchlist: Boolean) {
        val payload = buildJsonObject {
            putJsonArray(if (isShow) "shows" else "movies") {
                add(buildJsonObject { putJsonObject("ids") { put("imdb", imdbId) } })
            }
        }
        postJson(if (inWatchlist) "sync/watchlist" else "sync/watchlist/remove", payload.toString())
    }

    // --- HTTP -------------------------------------------------------------------------------

    private fun ids(media: TrackedMedia): JsonObject? {
        if (media.imdbId == null && media.tmdbId == null) return null
        return buildJsonObject {
            media.imdbId?.let { put("imdb", it) }
            media.tmdbId?.let { put("tmdb", it) }
        }
    }

    private fun mediaBody(media: TrackedMedia): Map<String, kotlinx.serialization.json.JsonElement>? {
        val ids = ids(media) ?: return null
        return if (media.isEpisode) mapOf(
            "show" to buildJsonObject { put("ids", ids); put("title", media.title) },
            "episode" to buildJsonObject { put("season", media.season ?: 0); put("number", media.episode ?: 0) }
        ) else mapOf("movie" to buildJsonObject { put("ids", ids); put("title", media.title); media.year?.let { put("year", it) } })
    }

    private suspend fun HttpRequestBuilder.traktHeaders(auth: Boolean = true) {
        header("trakt-api-version", "2")
        header("trakt-api-key", clientId)
        if (auth) accessToken()?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun get(path: String): kotlinx.serialization.json.JsonElement? {
        if (!isConfigured) return null
        val response = http.get("$API/$path") { traktHeaders() }
        return parse(response)
    }

    private suspend fun postJson(path: String, body: String, auth: Boolean = true): JsonObject? {
        if (!isConfigured) return null
        val response = http.post("$API/$path") {
            traktHeaders(auth)
            setBody(json(body))
        }
        return parse(response)?.asObject()
    }

    private suspend fun parse(response: HttpResponse): kotlinx.serialization.json.JsonElement? {
        if (response.status.value == 401) signOut()
        if (!response.status.isSuccess()) return null
        return runCatching { AddonJson.parseToJsonElement(response.bodyAsText()) }.getOrNull()
    }

    private fun json(text: String) = TextContent(text, HttpContentType.Application.Json)

    companion object {
        private const val API = "https://api.trakt.tv"
        private const val TOKEN_KEY = "trakt.token"

        fun poster(imdb: String) = "https://images.metahub.space/poster/medium/$imdb/img"
        fun background(imdb: String) = "https://images.metahub.space/background/medium/$imdb/img"
    }
}

