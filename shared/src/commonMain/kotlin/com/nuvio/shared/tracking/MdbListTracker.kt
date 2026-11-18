package com.nuvio.shared.tracking

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.bool
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
import com.nuvio.shared.util.encodeUrlComponent
import com.nuvio.shared.util.parseReleaseEpochMs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType as HttpContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MDBList as a tracker (Android data/mdblist): OAuth device sign-in with the app's client id,
 * scrobbling (start / pause / stop), watched history both ways, partly watched titles
 * (sync/playback) for Continue Watching, and the MDBList watchlist for the Library.
 * The ratings lookup (personal API key) stays in [MdbListService].
 */
class MdbListTracker(
    private val http: HttpClient,
    private val store: KeyValueStore,
    private val clientId: String
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
    val isConnected: Boolean get() = store.readToken(KEY) != null
    val username: String? get() = store.readToken(KEY)?.username

    // --- Sign-in ------------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun startSignIn(): DeviceSignIn {
        if (!isConfigured) throw TrackerException("This build has no MDBList client id")
        val response = http.post("$API/oauth/device-authorization/") {
            header("Accept", "application/json")
            setBody(form("client_id" to clientId, "scope" to "write"))
        }
        val body = parse(response)
        if (!response.status.isSuccess() || body == null) {
            throw TrackerException(body?.str("error_description") ?: body?.str("error")
                ?: "MDBList sign-in failed (HTTP ${response.status.value})")
        }
        val deviceCode = body.nonBlank("device_code") ?: throw TrackerException("MDBList returned no device code")
        val userCode = body.nonBlank("user_code") ?: throw TrackerException("MDBList returned no code")
        val verification = body.nonBlank("verification_uri_complete")
            ?: body.nonBlank("verification_uri")?.let { "$it?user_code=${encodeUrlComponent(userCode)}" }
            ?: "https://mdblist.com/oauth/device/?user_code=${encodeUrlComponent(userCode)}"
        return DeviceSignIn(
            providerId = "mdblist",
            userCode = userCode,
            verificationUrl = verification,
            deviceCode = deviceCode,
            intervalSeconds = body.int("interval")?.takeIf { it in 1..3600 } ?: 5
        )
    }

    /** True once approved (tokens stored); false while pending or asked to slow down. */
    @Throws(Exception::class)
    suspend fun pollSignIn(signIn: DeviceSignIn): Boolean {
        val response = http.post("$API/oauth/token/") {
            header("Accept", "application/json")
            setBody(form(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to signIn.deviceCode,
                "scope" to "write",
                "client_id" to clientId
            ))
        }
        val body = parse(response)
        if (!response.status.isSuccess()) {
            return when (body?.str("error")) {
                "authorization_pending", "slow_down", null -> false
                "expired_token" -> throw TrackerException("The code expired; start again")
                "access_denied" -> throw TrackerException("Sign-in was declined")
                "invalid_client", "unauthorized_client" -> throw TrackerException("MDBList rejected this app's client id")
                else -> if (response.status.value == 429 || response.status.value >= 500) false
                    else throw TrackerException(body.str("error_description") ?: "MDBList sign-in failed")
            }
        }
        storeTokens(body ?: return false, previous = null)
        runCatching { fetchUsername() }
        return true
    }

    /** Forgets the tokens here and revokes them on MDBList (best effort). */
    suspend fun signOut() {
        val token = store.readToken(KEY)
        store.writeToken(KEY, null)
        val refresh = token?.refreshToken ?: return
        runCatching {
            http.post("$API/oauth/revoke_token/") {
                setBody(form("client_id" to clientId, "token" to refresh, "token_type_hint" to "refresh_token"))
            }
        }
    }

    private fun storeTokens(body: JsonObject, previous: StoredToken?) {
        val access = body.nonBlank("access_token") ?: return
        val expiresIn = body.long("expires_in")?.takeIf { it > 0 } ?: 3600L
        store.writeToken(KEY, StoredToken(
            accessToken = access,
            refreshToken = body.nonBlank("refresh_token") ?: previous?.refreshToken,
            expiresAtMs = currentTimeMillis() + expiresIn * 1000,
            username = previous?.username
        ))
    }

    private suspend fun accessToken(forceRefresh: Boolean = false): String? {
        val token = store.readToken(KEY) ?: return null
        val refresh = token.refreshToken
        if ((!token.isExpired && !forceRefresh) || refresh == null) return token.accessToken
        val response = http.post("$API/oauth/token/") {
            header("Accept", "application/json")
            setBody(form("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId))
        }
        val body = parse(response)
        when {
            response.status.isSuccess() && body != null -> storeTokens(body, token)
            body?.str("error") in setOf("invalid_grant", "invalid_token") -> { store.writeToken(KEY, null); return null }
        }
        return store.readToken(KEY)?.accessToken
    }

    private suspend fun fetchUsername() {
        val user = getJson("/user").asObject() ?: return
        val name = user.nonBlank("username") ?: user.long("user_id")?.toString()
        store.readToken(KEY)?.let { if (name != null) store.writeToken(KEY, it.copy(username = name)) }
    }

    // --- Writes -------------------------------------------------------------------------------

    suspend fun scrobble(media: TrackedMedia, action: ScrobbleAction, progressPercent: Double) {
        val ids = ids(media) ?: return
        if (media.isEpisode && (media.season == null || media.episode == null)) return
        val payload = buildJsonObject {
            putJsonObject(if (media.isEpisode) "show" else "movie") {
                put("ids", ids)
                if (media.isEpisode) {
                    put("season", media.season ?: 0)
                    put("episode", media.episode ?: 0)
                }
            }
            put("progress", (progressPercent.coerceIn(0.0, 100.0) * 100).toLong() / 100.0)
        }
        runCatching { postJson("/scrobble/${action.path}", payload.toString()) }
    }

    suspend fun markWatched(media: TrackedMedia) {
        if (media.isEpisode) {
            val season = media.season ?: return
            val episode = media.episode ?: return
            markEpisodesWatched(media, listOf(season to episode))
            return
        }
        val ids = ids(media) ?: return
        val payload = buildJsonObject { putJsonArray("movies") { add(buildJsonObject { put("ids", ids) }) } }
        runCatching { postJson("/sync/watched", payload.toString()) }
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
        runCatching { postJson(if (remove) "/sync/watched/remove" else "/sync/watched", payload.toString()) }
    }

    /** Adds a movie or show to (or removes it from) the MDBList watchlist. */
    suspend fun setInWatchlist(imdbId: String?, tmdbId: Int?, isShow: Boolean, inWatchlist: Boolean) {
        if (imdbId == null && tmdbId == null) return
        val payload = buildJsonObject {
            putJsonArray(if (isShow) "shows" else "movies") {
                add(buildJsonObject {
                    putJsonObject("ids") {
                        imdbId?.let { put("imdb", it) }
                        tmdbId?.let { put("tmdb", it) }
                    }
                })
            }
        }
        runCatching { postJson(if (inWatchlist) "/watchlist/items/add" else "/watchlist/items/remove", payload.toString()) }
    }

    // --- Reads --------------------------------------------------------------------------------

    /** Watched movies ("movie:tt…") and episodes ("tt…:s:e"), like Trakt's watched state. */
    @Throws(Exception::class)
    suspend fun watchedKeys(): List<String> {
        val keys = mutableListOf<String>()
        pages("/sync/watched") { page ->
            page.objects("movies").forEach { row ->
                val movie = row.obj("movie") ?: row
                mediaId(movie)?.let { keys += "movie:$it" }
            }
            page.objects("shows").forEach { row ->
                val show = row.obj("show") ?: return@forEach
                val id = mediaId(show) ?: return@forEach
                row.objects("seasons").forEach { season ->
                    val number = season.int("number") ?: season.int("season") ?: return@forEach
                    season.objects("episodes").forEach { ep ->
                        val e = ep.int("number") ?: ep.int("episode") ?: return@forEach
                        keys += "$id:$number:$e"
                    }
                }
            }
            page.objects("episodes").forEach { row ->
                val episode = row.obj("episode") ?: return@forEach
                val show = row.obj("show") ?: episode.obj("show") ?: return@forEach
                val id = mediaId(show) ?: return@forEach
                val season = episode.int("season") ?: return@forEach
                val number = episode.int("number") ?: episode.int("episode") ?: return@forEach
                keys += "$id:$season:$number"
            }
        }
        return keys.distinct()
    }

    /** Partly watched movies and episodes (sync/playback) as Continue Watching entries. */
    @Throws(Exception::class)
    suspend fun playbackProgress(): List<WatchProgress> {
        val sessions = getJson("/sync/playback").asArray() ?: return emptyList()
        return sessions.mapNotNull { element ->
            val o = element.asObject() ?: return@mapNotNull null
            val percent = o.str("progress")?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return@mapNotNull null
            val updated = parseReleaseEpochMs(o.str("paused_at") ?: o.str("updated_at")) ?: currentTimeMillis()
            val runtime = o.int("runtime")?.takeIf { it > 0 }
            val duration = (runtime ?: 100).toLong() * 60_000L
            val position = (duration * percent / 100).toLong()
            if (o.str("type") == "movie") {
                val movie = o.obj("movie") ?: return@mapNotNull null
                val id = mediaId(movie) ?: return@mapNotNull null
                return@mapNotNull WatchProgress(
                    contentId = id, contentType = "movie", videoId = id,
                    name = movie.str("title") ?: id, poster = poster(movie, id), background = background(id),
                    positionMs = position, durationMs = duration, updatedAtMs = updated
                )
            }
            val episode = o.obj("episode") ?: return@mapNotNull null
            val show = o.obj("show") ?: episode.obj("show") ?: return@mapNotNull null
            val id = mediaId(show) ?: return@mapNotNull null
            val season = episode.int("season") ?: return@mapNotNull null
            val number = episode.int("number") ?: episode.int("episode") ?: return@mapNotNull null
            WatchProgress(
                contentId = id, contentType = "series", videoId = "$id:$season:$number",
                name = show.str("title") ?: id, poster = poster(show, id), background = background(id),
                season = season, episode = number, episodeTitle = episode.str("title") ?: episode.str("name"),
                positionMs = position, durationMs = duration, updatedAtMs = updated
            )
        }
    }

    /** The MDBList watchlist, newest first, for the Library. */
    @Throws(Exception::class)
    suspend fun watchlist(): List<MetaPreview> {
        val items = mutableListOf<MetaPreview>()
        pages("/watchlist/items", "&unified=true&append_to_response=poster") { page ->
            val rows = page.objects("items").ifEmpty { page.objects("movies") + page.objects("shows") }
            rows.forEach { row -> row.toWatchlistPreview()?.let { items += it } }
        }
        return items.distinctBy { it.id }
    }

    private fun JsonObject.toWatchlistPreview(): MetaPreview? {
        val type = str("mediatype") ?: str("type")
        val isShow = type == "show" || type == "series" || type == "tv"
        if (type != null && !isShow && type != "movie") return null
        val idsObj = obj("ids")
        val imdb = (nonBlank("imdb_id") ?: idsObj?.nonBlank("imdb"))?.takeIf { it.startsWith("tt") }
        val tmdb = idsObj?.long("tmdb") ?: long("id")
        val id = imdb ?: tmdb?.let { "tmdb:$it" } ?: return null
        return MetaPreview(
            id = id,
            type = if (isShow) ContentType.SERIES else ContentType.MOVIE,
            name = str("title") ?: str("name") ?: id,
            poster = image(str("poster"), "w500") ?: imdb?.let { TraktService.poster(it) },
            posterShape = PosterShape.POSTER,
            background = image(str("backdrop"), "w1280") ?: imdb?.let { TraktService.background(it) },
            logo = null,
            description = str("description"),
            releaseInfo = (int("release_year") ?: int("year"))?.toString(),
            imdbRating = null,
            genres = emptyList(),
            imdbId = imdb
        )
    }

    // --- HTTP ---------------------------------------------------------------------------------

    private fun ids(media: TrackedMedia): JsonObject? {
        if (media.imdbId == null && media.tmdbId == null) return null
        return buildJsonObject {
            media.imdbId?.let { put("imdb", it) }
            media.tmdbId?.let { put("tmdb", it) }
        }
    }

    /** "tt…" when known, else "tmdb:…". */
    private fun mediaId(media: JsonObject): String? {
        val ids = media.obj("ids") ?: media
        return (ids.nonBlank("imdb") ?: ids.nonBlank("imdbid"))?.takeIf { it.startsWith("tt") }
            ?: (ids.long("tmdb") ?: ids.long("tmdbid"))?.let { "tmdb:$it" }
    }

    private fun poster(media: JsonObject, id: String): String? =
        image(media.str("poster"), "w500") ?: id.takeIf { it.startsWith("tt") }?.let { TraktService.poster(it) }

    private fun background(id: String): String? = id.takeIf { it.startsWith("tt") }?.let { TraktService.background(it) }

    private fun image(value: String?, size: String): String? = when {
        value.isNullOrBlank() -> null
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("/") && !value.startsWith("//") -> "https://image.tmdb.org/t/p/$size$value"
        else -> null
    }

    /** Pages through a list endpoint (cursor or offset pagination, 1000 per page). */
    private suspend fun pages(path: String, extra: String = "", onPage: (JsonObject) -> Unit) {
        var query = "?limit=1000$extra"
        repeat(50) {
            val element = getJson(path, query) ?: return
            val page = element.asObject() ?: buildJsonObject { put("items", element) }
            onPage(page)
            val pagination = page.obj("pagination") ?: page
            val cursor = pagination.nonBlank("next_cursor")
            val offset = pagination.long("offset")
            val limit = pagination.long("limit")
            val total = pagination.long("total")
            val hasMore = pagination.bool("has_more") ?: (offset != null && limit != null && total != null && offset + limit < total)
            query = when {
                cursor != null -> "?limit=1000$extra&cursor=${encodeUrlComponent(cursor)}"
                hasMore && offset != null && limit != null -> "?limit=1000$extra&offset=${offset + limit}"
                else -> return
            }
        }
    }

    private suspend fun getJson(path: String, query: String = ""): JsonElement? {
        var token = accessToken() ?: return null
        var response = http.get("$API$path$query") { authorized(token) }
        if (response.status.value == 401) {
            token = accessToken(forceRefresh = true) ?: return null
            response = http.get("$API$path$query") { authorized(token) }
        }
        if (!response.status.isSuccess()) throw TrackerException("MDBList returned ${response.status.value}")
        return runCatching { AddonJson.parseToJsonElement(response.bodyAsText()) }.getOrNull()
    }

    private suspend fun postJson(path: String, body: String) {
        var token = accessToken() ?: return
        var response = http.post("$API$path") { authorized(token); setBody(TextContent(body, HttpContentType.Application.Json)) }
        if (response.status.value == 401) {
            token = accessToken(forceRefresh = true) ?: return
            response = http.post("$API$path") { authorized(token); setBody(TextContent(body, HttpContentType.Application.Json)) }
        }
        if (!response.status.isSuccess()) throw TrackerException("MDBList returned ${response.status.value}")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorized(token: String) {
        header("Authorization", "Bearer $token")
        header("Accept", "application/json")
    }

    private suspend fun parse(response: HttpResponse): JsonObject? =
        runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asObject() }.getOrNull()

    private fun form(vararg fields: Pair<String, String>) = TextContent(
        fields.joinToString("&") { (k, v) -> "${encodeUrlComponent(k)}=${encodeUrlComponent(v)}" },
        HttpContentType.Application.FormUrlEncoded
    )

    private companion object {
        const val API = "https://api.mdblist.com"
        const val KEY = "mdblist.token"
    }
}
