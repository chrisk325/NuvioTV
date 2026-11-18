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
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType as HttpContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
    private val clientId: String,
    /** Where list contents are kept between launches (tvOS: files in Caches; UserDefaults is small there). */
    private val cacheStore: KeyValueStore = store
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
        clearListCache(null)
        cacheStore.putString(LISTS_CACHE, null)
        accountId = null
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
        clearListCache(WATCHLIST_KEY)
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

    /** The MDBList watchlist (MDBList's own order), for the Library and the release calendar. */
    @Throws(Exception::class)
    suspend fun watchlist(): List<MetaPreview> = listItems(WATCHLIST_KEY, MdbListSort.DEFAULT)

    // --- Lists (Android MdbListLibraryRemote / MdbListLibraryWriter / MdbListLibrarySorter) ------

    private var accountId: Long? = null
    /** List key → order ("sort|order") → titles; mirrored to [cacheStore], one entry per list. */
    private val itemCache = mutableMapOf<String, MutableMap<String, List<MdbListEntry>>>()
    private val cacheJson = Json { ignoreUnknownKeys = true }

    /** Library orders, as Android offers them for MDBList lists. */
    val sorts: List<MdbListSort> get() = MdbListSort.entries

    fun sort(named: String): MdbListSort = MdbListSort.entries.firstOrNull { it.name == named } ?: MdbListSort.DEFAULT

    /** Lists hidden from the Library (Settings → Accounts → MDBList). The watchlist is always shown. */
    val hiddenListKeys: Set<String>
        get() = store.getString(HIDDEN_KEY)?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    fun setListHidden(key: String, hidden: Boolean) {
        if (key == WATCHLIST_KEY) return
        val next = if (hidden) hiddenListKeys + key else hiddenListKeys - key
        store.putString(HIDDEN_KEY, next.joinToString("\n"))
    }

    /**
     * The watchlist, then the account's own lists (static, then dynamic), lists synced from other
     * services (external) and lists of other users the account liked, as on Android.
     */
    @Throws(Exception::class)
    suspend fun lists(refresh: Boolean): List<MdbListLibraryList> {
        val previous = cachedLists()
        if (!refresh && previous != null) return previous
        val fetched = fetchLists()
        // Lists MDBList reports as changed (or gone) are downloaded again; unchanged ones keep their
        // cached titles. The watchlist has no update time, so a refresh always reloads it.
        val before = previous.orEmpty().associate { it.key to it.updatedAt }
        fetched.forEach { list ->
            if (list.key == WATCHLIST_KEY || list.updatedAt == null || before[list.key] != list.updatedAt) dropItems(list.key)
        }
        before.keys.filter { key -> fetched.none { it.key == key } }.forEach(::dropItems)
        cacheStore.putString(LISTS_CACHE, cacheJson.encodeToString(ListSerializer(CachedList.serializer()), fetched.map {
            CachedList(it.key, it.name, it.kind, it.isPrivate, it.description, it.mediaType, it.updatedAt)
        }))
        return fetched
    }

    /** The lists as last downloaded, or null before the first download. */
    fun cachedLists(): List<MdbListLibraryList>? = cacheStore.getString(LISTS_CACHE)?.let { raw ->
        runCatching { cacheJson.decodeFromString(ListSerializer(CachedList.serializer()), raw) }.getOrNull()
    }?.map { MdbListLibraryList(it.key, it.name, it.kind, it.isPrivate, it.description, it.mediaType, it.updatedAt) }

    private suspend fun fetchLists(): List<MdbListLibraryList> {
        val result = mutableListOf(MdbListLibraryList(WATCHLIST_KEY, "Watchlist", MdbListLibraryList.WATCHLIST, false, null, null))
        val account = account()
        val own = getJson("/lists/user", "?unified=false&sort=ranked").asArray().orEmpty().mapNotNull { it.asObject() }
            .filter { row -> account == null || row.long("user_id")?.let { it == account } != false }
        own.filter { it.str("type") == "static" && it.bool("dynamic") != true }.forEach { row ->
            val id = row.listId() ?: return@forEach
            result += MdbListLibraryList("$LIST_PREFIX$id", row.str("name") ?: return@forEach, MdbListLibraryList.PERSONAL,
                row.bool("private") ?: false, row.nonBlank("description"), row.mediaType(), row.updated())
        }
        own.filter { it.bool("dynamic") == true || it.str("type") == "dynamic" }.forEach { row ->
            val id = row.listId() ?: return@forEach
            result += MdbListLibraryList("$DYNAMIC_PREFIX$id", row.str("name") ?: return@forEach, MdbListLibraryList.DYNAMIC,
                row.bool("private") ?: false, row.nonBlank("description"), row.mediaType(), row.updated())
        }
        // External and liked lists are extras: when MDBList can't serve them the rest still loads.
        runCatching {
            getJson("/external/lists/user").asArray().orEmpty().mapNotNull { it.asObject() }.forEach { row ->
                val id = row.long("id")?.takeIf { it > 0 } ?: return@forEach
                result += MdbListLibraryList("$EXTERNAL_PREFIX$id", row.str("name") ?: return@forEach, MdbListLibraryList.EXTERNAL,
                    false, row.nonBlank("source"), row.mediaType(), row.updated())
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        runCatching {
            var offset = 0L
            repeat(20) {
                val page = getJson("/lists/liked", "?limit=100&offset=$offset").asObject() ?: return@runCatching
                page.objects("lists").forEach { row ->
                    if (account != null && row.long("user_id") == account) return@forEach
                    val id = row.long("id")?.takeIf { it > 0 } ?: return@forEach
                    result += MdbListLibraryList("$LIKED_PREFIX$id", row.str("name") ?: return@forEach, MdbListLibraryList.LIKED,
                        false, row.nonBlank("description"), row.mediaType(), row.updated())
                }
                val pagination = page.obj("pagination") ?: page
                val limit = pagination.long("limit") ?: 100
                val total = pagination.long("total")
                val more = pagination.bool("has_more") ?: (total != null && offset + limit < total)
                if (!more) return@runCatching
                offset += limit
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        return result.distinctBy { it.key }
    }

    /**
     * A list's titles in [sort] order. "Default" is the order the list's owner saved on MDBList;
     * added / released orders come from MDBList (it knows when each title was added), title order
     * is sorted here.
     */
    @Throws(Exception::class)
    suspend fun listItems(key: String, sort: MdbListSort): List<MetaPreview> {
        val entries = when (sort) {
            MdbListSort.DEFAULT, MdbListSort.TITLE_ASC, MdbListSort.TITLE_DESC -> entries(key, null, "asc")
            // MDBList returns the newest additions / releases for order=asc.
            MdbListSort.ADDED_DESC -> entries(key, "added", "asc")
            MdbListSort.ADDED_ASC -> entries(key, "added", "asc").asReversed()
            MdbListSort.RELEASED_DESC, MdbListSort.RELEASED_ASC -> {
                val newestFirst = entries(key, "released", "asc").let { list ->
                    // The direction is undocumented: check it against the release dates.
                    val dates = list.mapNotNull { it.releaseDate ?: it.preview.releaseInfo }
                    if (dates.isNotEmpty() && dates.first() < dates.last()) list.asReversed() else list
                }
                if (sort == MdbListSort.RELEASED_DESC) newestFirst else newestFirst.asReversed()
            }
        }
        val previews = entries.map { it.preview }
        return when (sort) {
            MdbListSort.TITLE_ASC -> previews.sortedBy { it.name.lowercase() }
            MdbListSort.TITLE_DESC -> previews.sortedByDescending { it.name.lowercase() }
            else -> previews
        }
    }

    private suspend fun entries(key: String, sort: String?, order: String): List<MdbListEntry> {
        val cacheKey = "$sort|$order"
        val stored = itemCache.getOrPut(key) { loadItems(key) }
        stored[cacheKey]?.let { return it }
        val items = linkedMapOf<String, MdbListEntry>()
        var stop = false
        val query = buildString {
            append("&unified=true&append_to_response=poster,description,genres")
            if (sort != null) append("&sort=$sort&order=$order")
        }
        pages(itemsPath(key), query, { stop }) { page ->
            val rows = page.objects("items").ifEmpty { page.objects("movies") + page.objects("shows") }
            val before = items.size
            rows.forEach { row -> row.toEntry()?.let { if (it.preview.id !in items) items[it.preview.id] = it } }
            // A read-only list repeating earlier titles means the server is looping.
            if (rows.isNotEmpty() && items.size == before && !isEditable(key)) stop = true
        }
        return items.values.toList().also {
            stored[cacheKey] = it
            saveItems(key, stored)
        }
    }

    /** Forgets cached titles of one list, or of every list (they download again when next shown). */
    fun clearListCache(key: String?) {
        if (key != null) return dropItems(key)
        (itemCache.keys + cachedLists().orEmpty().map { it.key } + WATCHLIST_KEY).toSet().forEach(::dropItems)
    }

    private fun dropItems(key: String) {
        itemCache.remove(key)
        cacheStore.putString(itemsCacheKey(key), null)
    }

    private fun itemsCacheKey(key: String) = "mdblist.items." + key.replace(':', '_')

    private fun loadItems(key: String): MutableMap<String, List<MdbListEntry>> {
        val raw = cacheStore.getString(itemsCacheKey(key)) ?: return mutableMapOf()
        val decoded = runCatching {
            cacheJson.decodeFromString(MapSerializer(String.serializer(), ListSerializer(CachedEntry.serializer())), raw)
        }.getOrNull() ?: return mutableMapOf()
        return decoded.mapValues { (_, rows) -> rows.map { it.toEntry() } }.toMutableMap()
    }

    private fun saveItems(key: String, items: Map<String, List<MdbListEntry>>) {
        val rows = items.mapValues { (_, list) -> list.map { CachedEntry.from(it) } }
        cacheStore.putString(itemsCacheKey(key),
            cacheJson.encodeToString(MapSerializer(String.serializer(), ListSerializer(CachedEntry.serializer())), rows))
    }

    @Throws(Exception::class)
    suspend fun createList(name: String, isPrivate: Boolean) {
        require(name.isNotBlank()) { "Enter a list name" }
        val body = buildJsonObject { put("name", name.trim()); put("private", isPrivate) }
        send("POST", "/lists/user/add", body.toString())
        cacheStore.putString(LISTS_CACHE, null)
    }

    @Throws(Exception::class)
    suspend fun updateList(key: String, name: String, isPrivate: Boolean) {
        require(name.isNotBlank()) { "Enter a list name" }
        val id = personalId(key)
        val body = buildJsonObject { put("name", name.trim()); put("private", isPrivate) }
        send("PUT", "/lists/$id", body.toString())
        cacheStore.putString(LISTS_CACHE, null)
    }

    @Throws(Exception::class)
    suspend fun deleteList(key: String) {
        send("DELETE", "/lists/${personalId(key)}", null)
        clearListCache(key)
        cacheStore.putString(LISTS_CACHE, null)
        setListHidden(key, false)
    }

    /** Lists a title can be added to (the watchlist and the account's own static lists) that hold it. */
    @Throws(Exception::class)
    suspend fun listsContaining(imdbId: String?, tmdbId: Int?, lists: List<MdbListLibraryList>): Set<String> {
        val ids = setOfNotNull(imdbId, tmdbId?.let { "tmdb:$it" })
        return lists.filter { it.acceptsItems }.filter { list ->
            entries(list.key, null, "asc").any { entry -> entry.preview.id in ids || entry.preview.imdbId in ids }
        }.map { it.key }.toSet()
    }

    /** Adds a movie or show to (or removes it from) a list it can hold. */
    @Throws(Exception::class)
    suspend fun setInList(key: String, imdbId: String?, tmdbId: Int?, isShow: Boolean, add: Boolean) {
        if (imdbId == null && tmdbId == null) throw TrackerException("An IMDb or TMDB id is needed")
        require(isEditable(key)) { "This list can't be changed" }
        val ids = buildJsonObject {
            imdbId?.let { put("imdb", it) }
            tmdbId?.let { put("tmdb", it) }
        }
        val payload = buildJsonObject {
            putJsonArray(if (isShow) "shows" else "movies") {
                // The watchlist takes {"ids": {...}}, static lists the ids themselves.
                add(if (key == WATCHLIST_KEY) buildJsonObject { put("ids", ids) } else ids)
            }
        }
        send("POST", "${itemsPath(key)}/${if (add) "add" else "remove"}", payload.toString())
        clearListCache(key)
    }

    private fun isEditable(key: String) = key == WATCHLIST_KEY || key.startsWith(LIST_PREFIX)

    private fun personalId(key: String): Long =
        key.removePrefix(LIST_PREFIX).toLongOrNull()?.takeIf { key.startsWith(LIST_PREFIX) && it > 0 }
            ?: throw TrackerException("Only your own static lists can be changed")

    private fun itemsPath(key: String): String = when {
        key == WATCHLIST_KEY -> "/watchlist/items"
        key.startsWith(EXTERNAL_PREFIX) -> "/external/lists/${key.removePrefix(EXTERNAL_PREFIX)}/items"
        key.startsWith(DYNAMIC_PREFIX) -> "/lists/${key.removePrefix(DYNAMIC_PREFIX)}/items"
        key.startsWith(LIKED_PREFIX) -> "/lists/${key.removePrefix(LIKED_PREFIX)}/items"
        else -> "/lists/${key.removePrefix(LIST_PREFIX)}/items"
    }

    private suspend fun account(): Long? {
        accountId?.let { return it }
        return runCatching { getJson("/user").asObject()?.long("user_id") }.getOrNull()?.also { accountId = it }
    }

    private fun JsonObject.listId(): Long? =
        long("id")?.takeIf { it > 0 } ?: this["ids"].asArray()?.singleOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }

    private fun JsonObject.updated(): String? = nonBlank("last_updated_at") ?: nonBlank("updated_at") ?: nonBlank("updated")

    private fun JsonObject.mediaType(): String? = when (str("mediatype")) {
        "movie" -> "movie"
        "show", "series", "tv" -> "series"
        else -> null
    }

    private fun JsonObject.toEntry(): MdbListEntry? {
        val type = str("mediatype") ?: str("type")
        val isShow = type == "show" || type == "series" || type == "tv"
        if (type != null && !isShow && type != "movie") return null
        val idsObj = obj("ids")
        val imdb = (nonBlank("imdb_id") ?: idsObj?.nonBlank("imdb"))?.takeIf { it.startsWith("tt") }
        val tmdb = idsObj?.long("tmdb") ?: long("id")
        val id = imdb ?: tmdb?.let { "tmdb:$it" } ?: return null
        val preview = MetaPreview(
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
        return MdbListEntry(preview, str("release_date") ?: str("released"))
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
    private suspend fun pages(path: String, extra: String = "", stop: () -> Boolean = { false }, onPage: (JsonObject) -> Unit) {
        var query = "?limit=1000$extra"
        repeat(50) {
            val element = getJson(path, query) ?: return
            val page = element.asObject() ?: buildJsonObject { put("items", element) }
            onPage(page)
            if (stop()) return
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

    private suspend fun postJson(path: String, body: String) = send("POST", path, body)

    private suspend fun send(method: String, path: String, body: String?) {
        suspend fun call(token: String) = http.request("$API$path") {
            this.method = io.ktor.http.HttpMethod.parse(method)
            authorized(token)
            if (body != null) setBody(TextContent(body, HttpContentType.Application.Json))
        }
        var token = accessToken() ?: throw TrackerException("Sign in to MDBList first")
        var response = call(token)
        if (response.status.value == 401) {
            token = accessToken(forceRefresh = true) ?: throw TrackerException("Sign in to MDBList again")
            response = call(token)
        }
        if (!response.status.isSuccess()) {
            val error = parse(response)
            throw TrackerException(error?.str("detail") ?: error?.str("error_description") ?: error?.str("error")
                ?: "MDBList returned ${response.status.value}")
        }
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

    companion object {
        private const val API = "https://api.mdblist.com"
        private const val KEY = "mdblist.token"
        private const val HIDDEN_KEY = "mdblist.hidden_lists"
        private const val LISTS_CACHE = "mdblist.lists.v1"
        const val WATCHLIST_KEY = "mdblist:watchlist"
        private const val LIST_PREFIX = "mdblist:list:"
        private const val DYNAMIC_PREFIX = "mdblist:dynamic:"
        private const val EXTERNAL_PREFIX = "mdblist:external:"
        private const val LIKED_PREFIX = "mdblist:liked:"
    }
}

/** Library list orders (Android LibrarySortOption.MdbListOptions). */
enum class MdbListSort(val label: String) {
    DEFAULT("MDBList order"),
    ADDED_DESC("Recently added"),
    ADDED_ASC("First added"),
    RELEASED_DESC("Newest release"),
    RELEASED_ASC("Oldest release"),
    TITLE_ASC("Title A–Z"),
    TITLE_DESC("Title Z–A")
}

/** One MDBList list as a Library tab. */
data class MdbListLibraryList(
    val key: String,
    val name: String,
    /** [WATCHLIST], [PERSONAL] (own static list), [DYNAMIC] (own, filter based), [EXTERNAL] or [LIKED]. */
    val kind: String,
    val isPrivate: Boolean,
    val description: String?,
    /** "movie" or "series" when the list holds only one kind, else null. */
    val mediaType: String?,
    /** MDBList's last change of the list; cached titles are kept while it stays the same. */
    val updatedAt: String? = null
) {
    /** Own static lists can be renamed, made private / public and deleted. */
    val isEditable: Boolean get() = kind == PERSONAL
    /** Titles can be added to / removed from the watchlist and own static lists only. */
    val acceptsItems: Boolean get() = kind == WATCHLIST || kind == PERSONAL

    fun accepts(isShow: Boolean): Boolean = acceptsItems && (mediaType == null || mediaType == if (isShow) "series" else "movie")

    companion object {
        const val WATCHLIST = "watchlist"
        const val PERSONAL = "personal"
        const val DYNAMIC = "dynamic"
        const val EXTERNAL = "external"
        const val LIKED = "liked"
    }
}

internal data class MdbListEntry(val preview: MetaPreview, val releaseDate: String?)

@Serializable
private data class CachedList(
    val key: String,
    val name: String,
    val kind: String,
    val isPrivate: Boolean,
    val description: String? = null,
    val mediaType: String? = null,
    val updatedAt: String? = null
)

@Serializable
private data class CachedEntry(
    val id: String,
    val isShow: Boolean,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val year: String? = null,
    val imdbId: String? = null,
    val releaseDate: String? = null
) {
    fun toEntry() = MdbListEntry(
        MetaPreview(
            id = id,
            type = if (isShow) ContentType.SERIES else ContentType.MOVIE,
            name = name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = null,
            description = description,
            releaseInfo = year,
            imdbRating = null,
            genres = emptyList(),
            imdbId = imdbId
        ),
        releaseDate
    )

    companion object {
        fun from(entry: MdbListEntry) = CachedEntry(
            id = entry.preview.id,
            isShow = entry.preview.type == ContentType.SERIES,
            name = entry.preview.name,
            poster = entry.preview.poster,
            background = entry.preview.background,
            description = entry.preview.description,
            year = entry.preview.releaseInfo,
            imdbId = entry.preview.imdbId,
            releaseDate = entry.releaseDate
        )
    }
}
