package com.nuvio.shared.cloud

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.bool
import com.nuvio.shared.data.int
import com.nuvio.shared.data.long
import com.nuvio.shared.data.nonBlank
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.str
import com.nuvio.shared.data.strings
import com.nuvio.shared.model.SavedItem
import com.nuvio.shared.model.WatchProgress
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.tracking.readToken
import com.nuvio.shared.tracking.writeToken
import com.nuvio.shared.tracking.StoredToken
import com.nuvio.shared.util.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

class CloudException(message: String) : Exception(message)

/** QR sign-in prompt (start_tv_login_session). */
data class CloudTvLogin(val code: String, val webUrl: String, val pollIntervalSeconds: Int, internal val nonce: String)

/** Remote add-on row (sync_push_addons / addons table). */
data class CloudAddon(val url: String, val enabled: Boolean, val sortOrder: Int)

/**
 * Nuvio account (Supabase) over plain REST: the same RPCs the Android app uses
 * (AuthManager TV login, AddonSyncService, LibrarySyncService, WatchProgressSyncService,
 * WatchedItemsSyncService), for the primary profile.
 */
class NuvioCloud(
    private val http: HttpClient,
    private val store: KeyValueStore,
    private val baseUrl: String,
    private val anonKey: String,
    /** Nuvio account profile this device profile syncs with (1 = primary). */
    private val profileId: Int = 1,
    /** Account sign-in is shared by all profiles on the device. */
    private val accountStore: KeyValueStore = store
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()
    val isSignedIn: Boolean get() = accountStore.readToken(TOKEN) != null
    val email: String? get() = accountStore.readToken(TOKEN)?.username

    private val clientId: String
        get() = store.getString("cloud.clientId") ?: buildString {
            repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
        }.also { store.putString("cloud.clientId", it) }


    // --- Auth -------------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun startTvLogin(deviceName: String): CloudTvLogin {
        val nonce = buildString { repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) } }
        val body = buildJsonObject {
            put("p_device_nonce", nonce)
            put("p_redirect_base_url", "https://nuvio.tv/tv-login")
            put("p_device_name", deviceName)
        }
        val row = rpc("start_tv_login_session", body, auth = false).asArray()?.firstOrNull().asObject()
            ?: throw CloudException("Couldn't start sign-in")
        return CloudTvLogin(
            code = row.nonBlank("code") ?: throw CloudException("No sign-in code"),
            webUrl = row.nonBlank("web_url") ?: "https://nuvio.tv/tv-login",
            pollIntervalSeconds = row.int("poll_interval_seconds") ?: 3,
            nonce = nonce
        )
    }

    /** True once approved and the session was exchanged; false while pending. */
    @Throws(Exception::class)
    suspend fun pollTvLogin(login: CloudTvLogin): Boolean {
        val row = rpc("poll_tv_login_session", buildJsonObject {
            put("p_code", login.code)
            put("p_device_nonce", login.nonce)
        }, auth = false).asArray()?.firstOrNull().asObject() ?: return false
        when (row.str("status")?.lowercase()) {
            "approved", "completed", "authorized" -> Unit
            "expired" -> throw CloudException("The code expired; start again")
            "denied", "rejected" -> throw CloudException("Sign-in was declined")
            else -> return false
        }
        val response = http.post("$baseUrl/functions/v1/tv-logins-exchange") {
            headers(auth = false)
            setBody(json(buildJsonObject { put("code", login.code); put("device_nonce", login.nonce) }))
        }
        storeSession(parse(response).asObject() ?: throw CloudException("Sign-in exchange failed"))
        return true
    }

    @Throws(Exception::class)
    suspend fun signInWithEmail(email: String, password: String) {
        val response = http.post("$baseUrl/auth/v1/token?grant_type=password") {
            headers(auth = false)
            setBody(json(buildJsonObject { put("email", email.trim()); put("password", password) }))
        }
        val body = parse(response).asObject()
        if (!response.status.isSuccess() || body == null) {
            throw CloudException(body?.str("error_description") ?: body?.str("msg") ?: "Sign-in failed")
        }
        storeSession(body)
    }

    fun signOut() = accountStore.writeToken(TOKEN, null)

    private fun storeSession(body: JsonObject) {
        val access = body.nonBlank("access_token") ?: throw CloudException("No access token")
        val expiresIn = body.long("expires_in") ?: 3600
        store.writeToken(
            TOKEN,
            StoredToken(access, body.nonBlank("refresh_token"), currentTimeMillis() + expiresIn * 1000, body.obj("user")?.nonBlank("email"))
        )
    }

    private suspend fun accessToken(): String? {
        val token = accountStore.readToken(TOKEN) ?: return null
        if (!token.isExpired || token.refreshToken == null) return token.accessToken
        val response = http.post("$baseUrl/auth/v1/token?grant_type=refresh_token") {
            headers(auth = false)
            setBody(json(buildJsonObject { put("refresh_token", token.refreshToken) }))
        }
        val body = parse(response).asObject()
        if (response.status.value == 400 || response.status.value == 401) { signOut(); return null }
        if (body != null && body.nonBlank("access_token") != null) {
            storeSession(body)
            accountStore.readToken(TOKEN)?.let { if (it.username == null) accountStore.writeToken(TOKEN, it.copy(username = token.username)) }
        }
        return accountStore.readToken(TOKEN)?.accessToken
    }

    // --- Add-ons ----------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun pullAddons(): List<CloudAddon> {
        val ownerElement = rpc("get_sync_owner", JsonObject(emptyMap()))
        val owner = ((ownerElement as? JsonPrimitive) ?: (ownerElement.asArray()?.firstOrNull() as? JsonPrimitive))
            ?.content?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw CloudException("Couldn't resolve the sync owner")
        val response = http.get("$baseUrl/rest/v1/addons?user_id=eq.$owner&profile_id=eq.$profileId&select=url,enabled,sort_order") { headers() }
        return parse(response).asArray().orEmpty().mapNotNull { e ->
            val o = e.asObject() ?: return@mapNotNull null
            CloudAddon(o.nonBlank("url") ?: return@mapNotNull null, o.bool("enabled") ?: true, o.int("sort_order") ?: 0)
        }.sortedBy { it.sortOrder }
    }

    @Throws(Exception::class)
    suspend fun pushAddons(addons: List<CloudAddon>) {
        rpc("sync_push_addons", buildJsonObject {
            put("p_addons", buildJsonArray {
                addons.forEachIndexed { index, a ->
                    add(buildJsonObject { put("url", a.url); put("sort_order", index); put("enabled", a.enabled) })
                }
            })
            put("p_profile_id", profileId)
            put("p_origin_client_id", clientId)
        })
    }

    // --- Library ----------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun pullLibrary(): List<SavedItem> {
        val all = mutableListOf<SavedItem>()
        var offset = 0
        while (true) {
            val page = rpc("sync_pull_library", buildJsonObject {
                put("p_profile_id", profileId); put("p_limit", 500); put("p_offset", offset)
            }).asArray().orEmpty()
            page.mapNotNullTo(all) { e ->
                val o = e.asObject() ?: return@mapNotNullTo null
                SavedItem(
                    id = o.nonBlank("content_id") ?: return@mapNotNullTo null,
                    type = o.str("content_type") ?: "movie",
                    name = o.str("name").orEmpty(),
                    poster = o.nonBlank("poster"),
                    background = o.nonBlank("background"),
                    posterShape = o.str("poster_shape")?.lowercase() ?: "poster",
                    savedAtMs = o.long("added_at") ?: 0
                )
            }
            if (page.size < 500) break
            offset += 500
        }
        return all
    }

    @Throws(Exception::class)
    suspend fun pushLibrary(items: List<SavedItem>) {
        if (items.isEmpty()) return
        rpc("sync_push_library_items", buildJsonObject {
            put("p_items", buildJsonArray {
                items.forEach { i ->
                    add(buildJsonObject {
                        put("content_id", i.id); put("content_type", i.type); put("name", i.name)
                        put("poster", i.poster); put("poster_shape", i.posterShape.uppercase()); put("background", i.background)
                        put("description", JsonNull); put("release_info", JsonNull); put("imdb_rating", JsonNull)
                        put("genres", JsonArray(emptyList())); put("addon_base_url", JsonNull); put("added_at", i.savedAtMs)
                    })
                }
            })
            put("p_profile_id", profileId)
            put("p_origin_client_id", clientId)
        })
    }

    @Throws(Exception::class)
    suspend fun deleteLibrary(id: String, type: String) {
        rpc("sync_delete_library_items", buildJsonObject {
            put("p_keys", buildJsonArray { add(buildJsonObject { put("content_id", id); put("content_type", type) }) })
            put("p_profile_id", profileId)
            put("p_origin_client_id", clientId)
        })
    }

    // --- Watch progress / watched -----------------------------------------------------------

    @Throws(Exception::class)
    suspend fun pullProgress(): List<WatchProgress> =
        rpc("sync_pull_watch_progress", buildJsonObject { put("p_profile_id", profileId); put("p_limit", 300) })
            .asArray().orEmpty().mapNotNull { e ->
                val o = e.asObject() ?: return@mapNotNull null
                WatchProgress(
                    contentId = o.nonBlank("content_id") ?: return@mapNotNull null,
                    contentType = o.str("content_type") ?: "movie",
                    videoId = o.nonBlank("video_id") ?: o.str("content_id").orEmpty(),
                    name = o.str("content_id").orEmpty(),
                    season = o.int("season"),
                    episode = o.int("episode"),
                    positionMs = o.long("position") ?: 0,
                    durationMs = o.long("duration") ?: 0,
                    updatedAtMs = o.long("last_watched") ?: 0
                )
            }

    @Throws(Exception::class)
    suspend fun pushProgress(entries: List<WatchProgress>) {
        if (entries.isEmpty()) return
        rpc("sync_push_watch_progress", buildJsonObject {
            put("p_entries", buildJsonArray {
                entries.forEach { p ->
                    add(buildJsonObject {
                        put("content_id", p.contentId); put("content_type", p.contentType); put("video_id", p.videoId)
                        p.season?.let { put("season", it) }; p.episode?.let { put("episode", it) }
                        put("position", p.positionMs); put("duration", p.durationMs); put("last_watched", p.updatedAtMs)
                        put("progress_key", if (p.season != null && p.episode != null) "${p.contentId}_s${p.season}e${p.episode}" else p.contentId)
                    })
                }
            })
            put("p_profile_id", profileId)
            put("p_origin_client_id", clientId)
        })
    }

    /** Watched keys ("movie:<id>" / "<id>:<s>:<e>"). */
    @Throws(Exception::class)
    suspend fun pullWatched(): List<String> {
        val keys = mutableListOf<String>()
        var page = 1
        while (page <= 50) {
            val rows = rpc("sync_pull_watched_items", buildJsonObject {
                put("p_profile_id", profileId); put("p_page", page); put("p_page_size", 1000)
            }).asArray().orEmpty()
            rows.forEach { e ->
                val o = e.asObject() ?: return@forEach
                val id = o.nonBlank("content_id") ?: return@forEach
                val s = o.int("season"); val ep = o.int("episode")
                keys += if (s != null && ep != null) "$id:$s:$ep" else "movie:$id"
            }
            if (rows.size < 1000) break
            page++
        }
        return keys
    }

    @Throws(Exception::class)
    suspend fun pushWatched(contentId: String, contentType: String, title: String, season: Int?, episode: Int?) {
        rpc("sync_push_watched_items", buildJsonObject {
            put("p_items", buildJsonArray {
                add(buildJsonObject {
                    put("content_id", contentId); put("content_type", contentType); put("title", title)
                    put("season", season?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("episode", episode?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("watched_at", currentTimeMillis())
                })
            })
            put("p_profile_id", profileId)
            put("p_origin_client_id", clientId)
        })
    }

    /** Many episodes of one show (season, episode) as watched, in one call. */
    @Throws(Exception::class)
    suspend fun pushWatchedEpisodes(contentId: String, contentType: String, title: String, episodes: List<Pair<Int, Int>>) {
        if (episodes.isEmpty()) return
        val now = currentTimeMillis()
        rpc("sync_push_watched_items", buildJsonObject {
            put("p_items", buildJsonArray {
                episodes.forEach { (season, episode) ->
                    add(buildJsonObject {
                        put("content_id", contentId); put("content_type", contentType); put("title", title)
                        put("season", season); put("episode", episode); put("watched_at", now)
                    })
                }
            })
            put("p_profile_id", profileId)
            put("p_origin_client_id", clientId)
        })
    }

    /** Removes watched entries: (season, episode) pairs, or the item itself when [episodes] is empty. */
    @Throws(Exception::class)
    suspend fun deleteWatched(contentId: String, episodes: List<Pair<Int, Int>>) {
        rpc("sync_delete_watched_items", buildJsonObject {
            put("p_profile_id", profileId)
            put("p_keys", buildJsonArray {
                if (episodes.isEmpty()) add(buildJsonObject { put("content_id", contentId) })
                episodes.forEach { (season, episode) ->
                    add(buildJsonObject { put("content_id", contentId); put("season", season); put("episode", episode) })
                }
            })
            put("p_origin_client_id", clientId)
        })
    }

    // --- HTTP -------------------------------------------------------------------------------

    private suspend fun HttpRequestBuilder.headers(auth: Boolean = true) {
        header("apikey", anonKey)
        header("Authorization", "Bearer ${if (auth) accessToken() ?: anonKey else anonKey}")
    }

    private suspend fun rpc(name: String, body: JsonObject, auth: Boolean = true): JsonElement {
        if (!isConfigured) throw CloudException("This build has no Nuvio account configuration")
        val response = http.post("$baseUrl/rest/v1/rpc/$name") {
            headers(auth)
            setBody(json(body))
        }
        if (!response.status.isSuccess()) {
            val message = runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asObject()?.str("message") }.getOrNull()
            throw CloudException(message ?: "$name failed (HTTP ${response.status.value})")
        }
        return parse(response) ?: JsonNull
    }

    private suspend fun parse(response: HttpResponse): JsonElement? =
        runCatching { AddonJson.parseToJsonElement(response.bodyAsText()) }.getOrNull()

    private fun json(body: JsonObject) = TextContent(body.toString(), ContentType.Application.Json)

    private companion object { const val TOKEN = "cloud.token" }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: String?) {
    put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
}


private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
