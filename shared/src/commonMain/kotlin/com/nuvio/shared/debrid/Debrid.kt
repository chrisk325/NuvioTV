package com.nuvio.shared.debrid

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.asString
import com.nuvio.shared.data.bool
import com.nuvio.shared.data.int
import com.nuvio.shared.data.long
import com.nuvio.shared.data.nonBlank
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.objects
import com.nuvio.shared.data.str
import com.nuvio.shared.model.AddonStreams
import com.nuvio.shared.model.Stream
import com.nuvio.shared.model.StreamBehaviorHints
import com.nuvio.shared.model.StreamDebridCache
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.encodeUrlComponent
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

// Port of app/.../core/debrid (Torbox, Premiumize, Real-Debrid resolvers, file selection,
// local cache checks and device-code sign-in).

data class DebridProvider(val id: String, val displayName: String, val shortName: String, val supportsDeviceCode: Boolean)

object DebridProviders {
    const val TORBOX_ID = "torbox"
    const val PREMIUMIZE_ID = "premiumize"
    const val REAL_DEBRID_ID = "realdebrid"

    val torbox = DebridProvider(TORBOX_ID, "Torbox", "TB", supportsDeviceCode = true)
    val premiumize = DebridProvider(PREMIUMIZE_ID, "Premiumize", "PM", supportsDeviceCode = true)
    val realDebrid = DebridProvider(REAL_DEBRID_ID, "Real-Debrid", "RD", supportsDeviceCode = false)

    val all: List<DebridProvider> = listOf(torbox, premiumize, realDebrid)

    fun byId(id: String?): DebridProvider? = all.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
}

sealed class DebridResolveResult {
    data class Success(val url: String, val filename: String?, val videoSize: Long?) : DebridResolveResult()
    data object MissingApiKey : DebridResolveResult()
    data object NotCached : DebridResolveResult()
    data object Stale : DebridResolveResult()
    data class Failed(val message: String) : DebridResolveResult()
}

class DebridException(message: String) : Exception(message)

/** Device-code sign-in prompt to show on screen. */
data class DebridDeviceCode(
    val providerId: String,
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    val intervalSeconds: Int
)

class DebridService(
    private val http: HttpClient,
    private val store: KeyValueStore,
    private val premiumizeClientId: String
) {
    // --- Credentials ------------------------------------------------------------------------

    fun apiKey(providerId: String): String = store.getString("debrid.key.$providerId").orEmpty()

    fun setApiKey(providerId: String, key: String) {
        store.putString("debrid.key.$providerId", key.trim().takeIf { it.isNotEmpty() })
    }

    fun isConnected(providerId: String): Boolean = apiKey(providerId).isNotBlank()

    /** Provider used to resolve torrents: the user's choice, else the first connected one. */
    var preferredProviderId: String
        get() = store.getString("debrid.preferred")?.takeIf { isConnected(it) }
            ?: DebridProviders.all.firstOrNull { isConnected(it.id) }?.id.orEmpty()
        set(value) = store.putString("debrid.preferred", value)

    val hasAnyProvider: Boolean get() = preferredProviderId.isNotEmpty()

    /** Show torrent streams that are not cached on the debrid service. */
    var showUncached: Boolean
        get() = store.getString("debrid.showUncached")?.toBooleanStrictOrNull() ?: true
        set(value) = store.putString("debrid.showUncached", value.toString())

    // --- Device code sign-in ----------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun startDeviceCode(providerId: String): DebridDeviceCode = when (providerId) {
        DebridProviders.TORBOX_ID -> {
            val body = json(http.get("$TORBOX/v1/api/user/auth/device/start?app=${encodeUrlComponent("Nuvio Apple TV")}"))
            val data = body.obj("data") ?: throw DebridException(body.str("detail") ?: "Torbox sign-in failed")
            DebridDeviceCode(
                providerId = providerId,
                userCode = data.nonBlank("code") ?: throw DebridException("Torbox returned no code"),
                verificationUrl = data.nonBlank("friendly_verification_url") ?: data.nonBlank("verification_url") ?: "https://tor.box/link",
                deviceCode = data.nonBlank("device_code") ?: throw DebridException("Torbox returned no device code"),
                intervalSeconds = data.int("interval") ?: 5
            )
        }
        DebridProviders.PREMIUMIZE_ID -> {
            if (premiumizeClientId.isBlank()) throw DebridException("This build has no Premiumize client id; paste an API key instead.")
            val body = json(http.submitForm("$PREMIUMIZE/token", Parameters.build {
                append("response_type", "device_code")
                append("client_id", premiumizeClientId)
            }))
            DebridDeviceCode(
                providerId = providerId,
                userCode = body.nonBlank("user_code") ?: throw DebridException("Premiumize returned no code"),
                verificationUrl = body.nonBlank("verification_uri") ?: "https://www.premiumize.me/device",
                deviceCode = body.nonBlank("device_code") ?: throw DebridException("Premiumize returned no device code"),
                intervalSeconds = body.int("interval") ?: 5
            )
        }
        else -> throw DebridException("Device sign-in isn't available for this service")
    }

    /** Returns true once the user approved the code (and stores the token), false while pending. */
    @Throws(Exception::class)
    suspend fun pollDeviceCode(code: DebridDeviceCode): Boolean {
        val token = when (code.providerId) {
            DebridProviders.TORBOX_ID -> {
                val response = http.post("$TORBOX/v1/api/user/auth/device/token") {
                    setBody(TextContent("""{"device_code":"${code.deviceCode}"}""", ContentType.Application.Json))
                }
                if (!response.status.isSuccess()) return false
                AddonJson.parseToJsonElement(response.bodyAsText()).asObject()?.obj("data")?.nonBlank("access_token")
            }
            DebridProviders.PREMIUMIZE_ID -> {
                val response = http.submitForm("$PREMIUMIZE/token", Parameters.build {
                    append("grant_type", "device_code")
                    append("code", code.deviceCode)
                    append("client_id", premiumizeClientId)
                })
                if (!response.status.isSuccess()) return false
                AddonJson.parseToJsonElement(response.bodyAsText()).asObject()?.nonBlank("access_token")
            }
            else -> null
        } ?: return false
        setApiKey(code.providerId, token)
        if (store.getString("debrid.preferred").isNullOrBlank()) preferredProviderId = code.providerId
        return true
    }

    // --- Cache checks -----------------------------------------------------------------------

    /** Marks torrent streams with their cache status on the preferred provider (Torbox / Premiumize). */
    suspend fun annotateCache(groups: List<AddonStreams>): List<AddonStreams> {
        val provider = DebridProviders.byId(preferredProviderId) ?: return groups
        val key = apiKey(provider.id)
        val hashes = groups.flatMap { it.streams }.filter { it.needsDebridResolve }
            .mapNotNull { it.effectiveInfoHash()?.lowercase() }.distinct()
        if (hashes.isEmpty()) return groups
        val cached: Set<String> = try {
            when (provider.id) {
                DebridProviders.TORBOX_ID -> torboxCached(key, hashes)
                DebridProviders.PREMIUMIZE_ID -> premiumizeCached(key, hashes)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return groups
        return groups.map { group ->
            group.copy(streams = group.streams.map { stream ->
                val hash = stream.effectiveInfoHash()?.lowercase()
                if (hash == null || !stream.needsDebridResolve) stream
                else stream.copy(debridCache = StreamDebridCache(provider.id, provider.shortName, hash in cached))
            })
        }
    }

    private suspend fun torboxCached(key: String, hashes: List<String>): Set<String>? {
        val response = http.post("$TORBOX/v1/api/torrents/checkcached?format=object") {
            bearer(key)
            setBody(TextContent("""{"hashes":[${hashes.joinToString(",") { "\"$it\"" }}]}""", ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) return null
        val body = AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: return null
        if (body.bool("success") == false) return null
        return body.obj("data")?.keys?.map { it.lowercase() }?.toSet() ?: emptySet()
    }

    private suspend fun premiumizeCached(key: String, hashes: List<String>): Set<String>? {
        val response = http.submitForm("$PREMIUMIZE/api/cache/check", Parameters.build {
            hashes.forEach { append("items[]", "magnet:?xt=urn:btih:$it") }
        }) { bearer(key) }
        if (!response.status.isSuccess()) return null
        val body = AddonJson.parseToJsonElement(response.bodyAsText()).asObject() ?: return null
        if (body.str("status").equals("error", ignoreCase = true)) return null
        val flags = body["response"].asArray() ?: return emptySet()
        return hashes.filterIndexed { index, _ -> flags.getOrNull(index).asString() == "true" }.toSet()
    }

    // --- Resolve ----------------------------------------------------------------------------

    /**
     * Turns a torrent / clientResolve stream into a direct HTTPS link via the add-on's named
     * service (clientResolve.service) or the user's preferred provider.
     */
    suspend fun resolve(stream: Stream, season: Int?, episode: Int?): DebridResolveResult {
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id?.takeIf { isConnected(it) }
            ?: preferredProviderId.takeIf { it.isNotEmpty() }
            ?: return DebridResolveResult.MissingApiKey
        val key = apiKey(providerId)
        val magnet = buildMagnet(stream) ?: return DebridResolveResult.Stale
        val target = FileTarget(
            filenames = listOfNotNull(stream.clientResolve?.filename, stream.behaviorHints?.filename),
            season = season ?: stream.clientResolve?.season,
            episode = episode ?: stream.clientResolve?.episode,
            fileIdx = stream.effectiveFileIdx()
        )
        return try {
            when (providerId) {
                DebridProviders.TORBOX_ID -> resolveTorbox(key, magnet, target)
                DebridProviders.PREMIUMIZE_ID -> resolvePremiumize(key, magnet, target)
                DebridProviders.REAL_DEBRID_ID -> resolveRealDebrid(key, magnet, target)
                else -> DebridResolveResult.MissingApiKey
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebridResolveResult.Failed(e.message ?: "Debrid request failed")
        }
    }

    /** Swift-friendly wrapper: returns a playable copy of the stream or throws with a readable reason. */
    @Throws(Exception::class)
    suspend fun resolveToPlayable(stream: Stream, season: Int, episode: Int): Stream {
        val result = resolve(stream, season.takeIf { it > 0 }, episode.takeIf { it > 0 })
        return when (result) {
            is DebridResolveResult.Success -> stream.copy(
                url = result.url,
                debridResolved = true,
                behaviorHints = (stream.behaviorHints ?: StreamBehaviorHints(null, null, null)).copy(
                    filename = result.filename ?: stream.behaviorHints?.filename,
                    videoSize = result.videoSize ?: stream.behaviorHints?.videoSize
                )
            )
            DebridResolveResult.MissingApiKey -> throw DebridException("Connect Torbox, Premiumize or Real-Debrid in Settings ▸ Debrid to play torrents.")
            DebridResolveResult.NotCached -> throw DebridException("This torrent isn't cached on your debrid service.")
            DebridResolveResult.Stale -> throw DebridException("The debrid service couldn't find a playable file in this torrent.")
            is DebridResolveResult.Failed -> throw DebridException(result.message)
        }
    }

    private suspend fun resolveTorbox(key: String, magnet: String, target: FileTarget): DebridResolveResult {
        val create = http.submitFormWithBinaryData("$TORBOX/v1/api/torrents/createtorrent", formData {
            append("magnet", magnet)
            append("add_only_if_cached", "true")
            append("allow_zip", "false")
        }) { bearer(key) }
        when (create.status.value) {
            401, 403 -> return DebridResolveResult.Failed("Torbox rejected the API key")
            409 -> return DebridResolveResult.NotCached
        }
        val createBody = jsonOrNull(create) ?: return DebridResolveResult.Stale
        if (createBody.bool("success") == false) {
            val detail = (createBody.str("detail") ?: createBody.str("error")).orEmpty().lowercase()
            return if ("cache" in detail) DebridResolveResult.NotCached else DebridResolveResult.Stale
        }
        val data = createBody.obj("data") ?: return DebridResolveResult.Stale
        val torrentId = data.int("torrent_id") ?: data.int("id") ?: return DebridResolveResult.Stale

        val info = jsonOrNull(http.get("$TORBOX/v1/api/torrents/mylist?id=$torrentId&bypass_cache=true") { bearer(key) })
            ?: return DebridResolveResult.Stale
        val files = info.obj("data")?.objects("files").orEmpty().map { f ->
            DebridFile(
                id = f.int("id")?.toString(),
                path = listOfNotNull(f.nonBlank("name"), f.nonBlank("absolute_path"), f.nonBlank("short_name")).firstOrNull().orEmpty(),
                size = f.long("size") ?: 0,
                isVideo = f.str("mimetype").orEmpty().startsWith("video/", ignoreCase = true)
            )
        }
        val file = selectDebridFile(files, target) ?: return DebridResolveResult.Stale
        val link = jsonOrNull(http.get(
            "$TORBOX/v1/api/torrents/requestdl?token=${encodeUrlComponent(key)}&torrent_id=$torrentId&file_id=${file.id}" +
                "&zip_link=false&redirect=false&append_name=false"
        ) { bearer(key) })?.nonBlank("data") ?: return DebridResolveResult.Stale
        return DebridResolveResult.Success(link, file.name, file.size.takeIf { it > 0 })
    }

    private suspend fun resolvePremiumize(key: String, magnet: String, target: FileTarget): DebridResolveResult {
        val response = http.submitForm("$PREMIUMIZE/api/transfer/directdl", Parameters.build { append("src", magnet) }) { bearer(key) }
        if (response.status.value == 401 || response.status.value == 403) return DebridResolveResult.Failed("Premiumize rejected the API key")
        val body = jsonOrNull(response) ?: return DebridResolveResult.Stale
        if (body.str("status").equals("error", ignoreCase = true)) {
            val message = listOfNotNull(body.str("message"), body.str("code")).joinToString(" ").lowercase()
            return if ("cache" in message || "not found" in message) DebridResolveResult.NotCached else DebridResolveResult.Stale
        }
        val files = body.objects("content").mapNotNull { f ->
            val link = f.nonBlank("link") ?: return@mapNotNull null
            DebridFile(id = link, path = f.str("path").orEmpty(), size = f.long("size") ?: 0, isVideo = false)
        }
        val file = selectDebridFile(files, target) ?: return DebridResolveResult.Stale
        return DebridResolveResult.Success(file.id!!, file.name, file.size.takeIf { it > 0 })
    }

    private suspend fun resolveRealDebrid(key: String, magnet: String, target: FileTarget): DebridResolveResult {
        val add = http.submitForm("$REAL_DEBRID/torrents/addMagnet", Parameters.build { append("magnet", magnet) }) { bearer(key) }
        if (add.status.value == 401 || add.status.value == 403) return DebridResolveResult.Failed("Real-Debrid rejected the API key")
        val torrentId = jsonOrNull(add)?.nonBlank("id") ?: return DebridResolveResult.Stale
        var resolved = false
        try {
            val before = jsonOrNull(http.get("$REAL_DEBRID/torrents/info/$torrentId") { bearer(key) }) ?: return DebridResolveResult.Stale
            val files = before.objects("files").map { f ->
                DebridFile(id = f.int("id")?.toString(), path = f.str("path").orEmpty(), size = f.long("bytes") ?: 0, isVideo = false)
            }
            val file = selectDebridFile(files, target) ?: return DebridResolveResult.Stale
            val select = http.submitForm("$REAL_DEBRID/torrents/selectFiles/$torrentId", Parameters.build { append("files", file.id.orEmpty()) }) { bearer(key) }
            if (!select.status.isSuccess() && select.status.value != 202) return DebridResolveResult.Stale
            val after = jsonOrNull(http.get("$REAL_DEBRID/torrents/info/$torrentId") { bearer(key) }) ?: return DebridResolveResult.Stale
            if (!after.str("status").equals("downloaded", ignoreCase = true)) return DebridResolveResult.NotCached
            val link = after["links"].asArray()?.firstNotNullOfOrNull { it.asString()?.takeIf(String::isNotBlank) }
                ?: return DebridResolveResult.Stale
            val unrestrict = jsonOrNull(http.submitForm("$REAL_DEBRID/unrestrict/link", Parameters.build { append("link", link) }) { bearer(key) })
                ?: return DebridResolveResult.Stale
            val url = unrestrict.nonBlank("download") ?: return DebridResolveResult.Stale
            resolved = true
            return DebridResolveResult.Success(url, unrestrict.nonBlank("filename") ?: file.name, unrestrict.long("filesize") ?: file.size)
        } finally {
            if (!resolved) runCatching { http.delete("$REAL_DEBRID/torrents/delete/$torrentId") { bearer(key) } }
        }
    }

    // --- Helpers ----------------------------------------------------------------------------

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(key: String) {
        header(HttpHeaders.Authorization, "Bearer $key")
    }

    private suspend fun json(response: HttpResponse): JsonObject =
        jsonOrNull(response) ?: throw DebridException("Unexpected response (HTTP ${response.status.value})")

    private suspend fun jsonOrNull(response: HttpResponse): JsonObject? {
        if (!response.status.isSuccess() && response.status.value != 202) return null
        return runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asObject() }.getOrNull()
    }

    companion object {
        private const val TORBOX = "https://api.torbox.app"
        private const val PREMIUMIZE = "https://www.premiumize.me"
        private const val REAL_DEBRID = "https://api.real-debrid.com/rest/1.0"

        /** Port of DebridMagnetBuilder.fromStream. */
        fun buildMagnet(stream: Stream): String? {
            listOfNotNull(stream.clientResolve?.magnetUri, stream.url, stream.externalUrl)
                .firstOrNull { it.trimStart().startsWith("magnet:", ignoreCase = true) }
                ?.let { return it }
            val hash = stream.effectiveInfoHash()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return buildString {
                append("magnet:?xt=urn:btih:")
                append(hash)
                (stream.behaviorHints?.filename ?: stream.clientResolve?.torrentName)?.trim()?.takeIf { it.isNotBlank() }?.let {
                    append("&dn="); append(encodeUrlComponent(it))
                }
                (stream.sources.orEmpty() + stream.clientResolve?.sources.orEmpty())
                    .mapNotNull { source ->
                        val value = source.trim()
                        if (value.isBlank() || value.startsWith("dht:", ignoreCase = true)) null
                        else value.removePrefix("tracker:").trim().takeIf { it.isNotBlank() }
                    }
                    .distinct()
                    .forEach { append("&tr="); append(encodeUrlComponent(it)) }
            }
        }
    }
}

internal data class FileTarget(val filenames: List<String>, val season: Int?, val episode: Int?, val fileIdx: Int?)

internal data class DebridFile(val id: String?, val path: String, val size: Long, val isVideo: Boolean) {
    val name: String get() = path.replace('\\', '/').substringAfterLast('/')
    val isPlayable: Boolean get() = isVideo || VIDEO_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }
}

private val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".wmv", ".flv")

/** Port of DebridFileSelection.selectDebridFile. */
internal fun selectDebridFile(files: List<DebridFile>, target: FileTarget): DebridFile? {
    val playable = files.filter { it.isPlayable }
    if (playable.isEmpty()) return null
    fun norm(s: String) = s.trim().replace('\\', '/').removePrefix("/")
    val names = target.filenames.map(::norm).filter { it.isNotBlank() }.distinct()
    for (name in names) {
        for (ignoreCase in listOf(false, true)) {
            val m = playable.filter { val p = norm(it.path); p.equals(name, ignoreCase) || (name.contains('/') && p.endsWith("/$name", ignoreCase)) }
            if (m.isNotEmpty()) return m.singleOrNull()
        }
        val base = name.substringAfterLast('/')
        for (ignoreCase in listOf(false, true)) {
            val m = playable.filter { norm(it.path).substringAfterLast('/').equals(base, ignoreCase) }
            if (m.isNotEmpty()) return m.singleOrNull()
        }
    }
    val pattern = if (target.season != null && target.episode != null) {
        Regex("(?<![a-z0-9])(?:s0*${target.season}e0*${target.episode}|0*${target.season}x0*${target.episode})(?![0-9])", RegexOption.IGNORE_CASE)
    } else null
    if (pattern != null) {
        val m = playable.filter { pattern.containsMatchIn(it.name) }
        if (m.isNotEmpty()) return m.singleOrNull() ?: m.maxByOrNull { it.size }
    }
    if (names.isNotEmpty() || pattern != null) return null
    target.fileIdx?.let { index -> return files.getOrNull(index)?.takeIf { it.isPlayable } }
    return playable.maxByOrNull { it.size }
}
